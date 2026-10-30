#include <jni.h>
#include <android/bitmap.h>
#include <cstdint>
#include <cstring>
#include <cmath>

namespace {
void error(JNIEnv* e,const char* message) {e->ThrowNew(e->FindClass("java/lang/IllegalArgumentException"),message);}
float unpack(uint16_t h) {
    uint32_t sign=(h&0x8000u)<<16,exponent=(h>>10)&31u,fraction=h&1023u,bits;
    if(exponent==0) {
        if(fraction==0)bits=sign;
        else {exponent=113;while(!(fraction&1024u)) {fraction<<=1;--exponent;}
            bits=sign|(exponent<<23)|((fraction&1023u)<<13);}
    } else bits=sign|((exponent==31?255:exponent+112)<<23)|(fraction<<13);
    float value;std::memcpy(&value,&bits,4);return value;
}
uint16_t pack(float value) {
    uint32_t bits;std::memcpy(&bits,&value,4);uint32_t sign=(bits>>16)&0x8000u,absolute=bits&0x7fffffffu;
    if(absolute<0x38800000u) {
        if(absolute<0x33000000u)return static_cast<uint16_t>(sign);
        uint32_t shift=126-(absolute>>23),mantissa=(absolute&0x7fffffu)|0x800000u;
        uint32_t top=mantissa>>shift,remainder=mantissa&((1u<<shift)-1),halfway=1u<<(shift-1);
        return static_cast<uint16_t>(sign|(top+((remainder>halfway||(remainder==halfway&&(top&1u)))?1:0)));
    }
    uint32_t top=absolute>>13,remainder=absolute&8191u;
    return static_cast<uint16_t>(sign|(top-0x1c000u+((remainder>4096u||(remainder==4096u&&(top&1u)))?1:0)));
}
struct Locked {
    JNIEnv* env;jobject image;void* pixels=nullptr;AndroidBitmapInfo info{};
    Locked(JNIEnv* e,jobject b):env(e),image(b) {
        // API 29's NDK imageInfo omits F16 and reports FORMAT_NONE for valid F16
        // storage. Use the public Java Config on every API; keep getInfo for stride.
        auto bitmapClass=e->FindClass("android/graphics/Bitmap");if(!bitmapClass)return;
        auto configClass=e->FindClass("android/graphics/Bitmap$Config");if(!configClass)return;
        auto getConfig=e->GetMethodID(bitmapClass,"getConfig","()Landroid/graphics/Bitmap$Config;");if(!getConfig)return;
        auto f16Field=e->GetStaticFieldID(configClass,"RGBA_F16","Landroid/graphics/Bitmap$Config;");if(!f16Field)return;
        auto actual=e->CallObjectMethod(b,getConfig);if(e->ExceptionCheck())return;
        auto expected=e->GetStaticObjectField(configClass,f16Field);if(e->ExceptionCheck())return;
        bool isF16=e->IsSameObject(actual,expected);
        e->DeleteLocalRef(actual);e->DeleteLocalRef(expected);
        e->DeleteLocalRef(configClass);e->DeleteLocalRef(bitmapClass);
        if(!isF16) {error(e,"需要RGBA_F16软件位图");return;}
        int result=AndroidBitmap_getInfo(e,b,&info);if(e->ExceptionCheck())return;
        if(result!=ANDROID_BITMAP_RESULT_SUCCESS||info.width==0||info.height==0||
           uint64_t(info.stride)<uint64_t(info.width)*8||info.stride%sizeof(uint16_t)!=0) {error(e,"高位深位图尺寸或行跨度无效");return;}
        if(AndroidBitmap_lockPixels(e,b,&pixels)!=ANDROID_BITMAP_RESULT_SUCCESS)error(e,"无法锁定高位深位图");
    }
    ~Locked() {if(pixels)AndroidBitmap_unlockPixels(env,image);}
};
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_ai_limbs_plugins_artstudio_ArtNativePixels_readNative(JNIEnv* env,jobject,jobject bitmap,jint x,jint y,jint width,jint height) {
    Locked image(env,bitmap);if(!image.pixels)return nullptr;
    if(x<0||y<0||width<=0||height<=0||int64_t(x)+width>image.info.width||int64_t(y)+height>image.info.height||int64_t(width)*height*4>INT32_MAX) {error(env,"像素读取矩形无效");return nullptr;}
    auto result=env->NewFloatArray(width*height*4);if(!result)return nullptr;
    auto values=env->GetFloatArrayElements(result,nullptr);if(!values)return nullptr;
    for(int row=0;row<height;++row) {
        auto source=reinterpret_cast<const uint16_t*>(static_cast<uint8_t*>(image.pixels)+size_t(y+row)*image.info.stride)+size_t(x)*4;
        for(int n=0;n<width*4;++n)values[row*width*4+n]=unpack(source[n]);
    }
    env->ReleaseFloatArrayElements(result,values,0);return result;
}
extern "C" JNIEXPORT void JNICALL
Java_com_ai_limbs_plugins_artstudio_ArtNativePixels_writeNative(JNIEnv* env,jobject,jobject bitmap,jfloatArray input) {
    Locked image(env,bitmap);if(!image.pixels)return;
    if(int64_t(image.info.width)*image.info.height*4!=env->GetArrayLength(input)) {error(env,"像素数组尺寸无效");return;}
    auto values=env->GetFloatArrayElements(input,nullptr);if(!values)return;
    const size_t count=size_t(image.info.width)*image.info.height*4;
    for(size_t n=0;n<count;++n)if(!std::isfinite(values[n])||std::fabs(values[n])>65504.f||(n%4==3&&(values[n]<0||values[n]>1))) {
        env->ReleaseFloatArrayElements(input,values,JNI_ABORT);error(env,"像素超出有限RGBA_F16范围");return;
    }
    for(uint32_t row=0;row<image.info.height;++row) {
        auto target=reinterpret_cast<uint16_t*>(static_cast<uint8_t*>(image.pixels)+size_t(row)*image.info.stride);
        for(uint32_t n=0;n<image.info.width*4;++n)target[n]=pack(values[size_t(row)*image.info.width*4+n]);
    }
    env->ReleaseFloatArrayElements(input,values,JNI_ABORT);
}
