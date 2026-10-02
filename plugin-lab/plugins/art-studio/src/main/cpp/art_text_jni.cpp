#include <jni.h>
#include <hb.h>
#include <hb-ot.h>
#include <cmath>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace {
void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), message);
}
std::string utf8(JNIEnv* env, jstring s) {
    const char* p = env->GetStringUTFChars(s, nullptr);
    if (!p) return {};
    std::string value(p); env->ReleaseStringUTFChars(s, p); return value;
}
template<class T, void (*Destroy)(T*)> using Owned = std::unique_ptr<T, decltype(Destroy)>;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_ai_limbs_plugins_artstudio_ArtTextShaper_shapeNative(JNIEnv* env, jobject,
    jobject data, jint index, jstring axes, jstring text, jint start, jint end, jfloat size,
    jstring direction, jstring script, jstring language, jstring features) {
    auto* bytes = static_cast<const char*>(env->GetDirectBufferAddress(data));
    const auto capacity = env->GetDirectBufferCapacity(data);
    const auto length = env->GetStringLength(text);
    if (!bytes || capacity <= 0 || capacity > UINT32_MAX || index < 0 || start < 0 ||
        end <= start || end > length || length > 8192 || !std::isfinite(size) || size < 6 || size > 512) {
        fail(env, "Invalid explicit font or shaping range"); return nullptr;
    }
    const std::string axisText = utf8(env, axes), dirText = utf8(env, direction),
        scriptText = utf8(env, script), langText = utf8(env, language), featureText = utf8(env, features);
    if (env->ExceptionCheck()) return nullptr;
    Owned<hb_blob_t, hb_blob_destroy> blob(hb_blob_create(bytes, static_cast<unsigned>(capacity),
        HB_MEMORY_MODE_READONLY, nullptr, nullptr), hb_blob_destroy);
    Owned<hb_face_t, hb_face_destroy> face(hb_face_create(blob.get(), index), hb_face_destroy);
    if (hb_face_get_glyph_count(face.get()) == 0) { fail(env, "Invalid OpenType font"); return nullptr; }
    Owned<hb_font_t, hb_font_destroy> font(hb_font_create(face.get()), hb_font_destroy);
    hb_ot_font_set_funcs(font.get());
    hb_font_set_scale(font.get(), std::lround(size * 64), std::lround(size * 64));
    std::vector<hb_variation_t> variations;
    size_t at = 0;
    while (at < axisText.size()) {
        const auto stop = axisText.find(',', at);
        const auto token = axisText.substr(at, stop == std::string::npos ? stop : stop - at);
        hb_variation_t v;
        if (!hb_variation_from_string(token.c_str(), -1, &v)) { fail(env, "Invalid font variation"); return nullptr; }
        variations.push_back(v);
        if (stop == std::string::npos) break;
        at = stop + 1;
    }
    hb_font_set_variations(font.get(), variations.data(), variations.size());
    Owned<hb_buffer_t, hb_buffer_destroy> buffer(hb_buffer_create(), hb_buffer_destroy);
    const jchar* chars = env->GetStringChars(text, nullptr);
    if (!chars) return nullptr;
    hb_buffer_add_utf16(buffer.get(), reinterpret_cast<const uint16_t*>(chars), length, start, end - start);
    env->ReleaseStringChars(text, chars);
    hb_buffer_set_cluster_level(buffer.get(), HB_BUFFER_CLUSTER_LEVEL_MONOTONE_GRAPHEMES);
    const auto dir = hb_direction_from_string(dirText.c_str(), -1);
    if (dir == HB_DIRECTION_INVALID) { fail(env, "Invalid text direction"); return nullptr; }
    hb_buffer_set_direction(buffer.get(), dir);
    hb_buffer_set_script(buffer.get(), hb_script_from_string(scriptText.c_str(), -1));
    hb_buffer_set_language(buffer.get(), hb_language_from_string(langText.c_str(), -1));
    std::vector<hb_feature_t> settings;
    at = 0;
    while (at < featureText.size()) {
        const auto stop = featureText.find(',', at);
        const auto token = featureText.substr(at, stop == std::string::npos ? stop : stop - at);
        hb_feature_t f;
        if (!hb_feature_from_string(token.c_str(), -1, &f)) { fail(env, "Invalid OpenType feature"); return nullptr; }
        settings.push_back(f);
        if (stop == std::string::npos) break;
        at = stop + 1;
    }
    hb_shape(font.get(), buffer.get(), settings.data(), settings.size());
    unsigned count = 0;
    auto* info = hb_buffer_get_glyph_infos(buffer.get(), &count);
    auto* pos = hb_buffer_get_glyph_positions(buffer.get(), nullptr);
    if (!hb_buffer_allocation_successful(buffer.get()) || count > 32768) {
        fail(env, "Text shaping exceeds work budget"); return nullptr;
    }
    std::vector<jfloat> result; result.reserve(count * 6);
    for (unsigned i = 0; i < count; ++i) {
        if (info[i].codepoint == 0) { fail(env, "Selected font is missing a shaped glyph; select an explicit font"); return nullptr; }
        result.insert(result.end(), {static_cast<float>(info[i].codepoint), static_cast<float>(info[i].cluster),
            pos[i].x_advance / 64.f, -pos[i].y_advance / 64.f, pos[i].x_offset / 64.f, -pos[i].y_offset / 64.f});
    }
    auto array = env->NewFloatArray(result.size());
    if (array) env->SetFloatArrayRegion(array, 0, result.size(), result.data());
    return array;
}
