# Android 支持的结构比较

原测试使用 JSONObject.similar 比较分页重组后的完整交付结果及首屏 ai_limbs_outcome。Android 编译接口缺少该方法，测试尚未开始运行即失败。

改为测试内部的 assertJsonEquals。对象必须具有完全相同的键集合，每个值递归比较；数组必须长度相同并保持元素顺序；标量使用 JUnit assertEquals。对象键顺序不影响结论，也不允许遗漏或新增字段。

所有 JSON 访问均使用 keys、get 和 length。运行代码与依赖不变，版本升为 0.0.18，applicationId 为 com.ai.limbs.payload.chatgptprobe.v018。

[DONE]
