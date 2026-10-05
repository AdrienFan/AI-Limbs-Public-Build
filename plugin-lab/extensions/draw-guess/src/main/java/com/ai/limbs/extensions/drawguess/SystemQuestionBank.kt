package com.ai.limbs.extensions.drawguess

import java.security.SecureRandom

internal enum class QuestionDifficulty(val label: String) {
    LOW("低"), MEDIUM("中"), HIGH("高"), RANDOM("随机难度")
}

internal data class SystemQuestion(val word: String, val difficulty: QuestionDifficulty,
    val category: String, val hint1: String, val hint2: String)

/** Authored local content; RANDOM is a selection scope rather than a fourth word bank. */
internal val SYSTEM_QUESTIONS: List<SystemQuestion> = listOf(
    SystemQuestion("苹果", QuestionDifficulty.LOW, "水果", "一种常见水果", "通常近圆形，顶部有果柄，可呈红色或绿色"),
    SystemQuestion("香蕉", QuestionDifficulty.LOW, "水果", "一种热带水果", "外皮常为黄色，形状细长弯曲，食用时剥皮"),
    SystemQuestion("西瓜", QuestionDifficulty.LOW, "水果", "夏天常吃的水果", "外皮绿色，里面常是红色果肉和黑色籽"),
    SystemQuestion("葡萄", QuestionDifficulty.LOW, "水果", "成串生长的水果", "小颗粒聚在一起，可呈紫色或绿色"),
    SystemQuestion("草莓", QuestionDifficulty.LOW, "水果", "一种红色小水果", "近心形，表面有许多小籽，顶部有绿叶"),
    SystemQuestion("雨伞", QuestionDifficulty.LOW, "物品", "一种挡住天气影响的工具", "有长柄和撑开的伞面，下雨时举在头顶"),
    SystemQuestion("钥匙", QuestionDifficulty.LOW, "物品", "一种开锁工具", "常为金属制成，一端有齿，能插进锁孔"),
    SystemQuestion("剪刀", QuestionDifficulty.LOW, "物品", "一种裁剪工具", "两个带指环的柄连接两片交叉刀刃"),
    SystemQuestion("牙刷", QuestionDifficulty.LOW, "物品", "一种清洁用品", "有细长手柄和刷毛，用来清洁口腔"),
    SystemQuestion("眼镜", QuestionDifficulty.LOW, "物品", "一种戴在脸上的用品", "两片镜片由镜架连接，支架挂在耳朵上"),
    SystemQuestion("闹钟", QuestionDifficulty.LOW, "物品", "一种提醒时间的用品", "到设定时间会响，常画成圆盘上方有两个铃"),
    SystemQuestion("灯泡", QuestionDifficulty.LOW, "物品", "一种照明部件", "常有圆鼓鼓的玻璃外壳和金属螺口"),
    SystemQuestion("书包", QuestionDifficulty.LOW, "物品", "学生常用的用品", "有背带，用来装课本和文具，背着去上学"),
    SystemQuestion("气球", QuestionDifficulty.LOW, "物品", "庆祝活动常见的装饰", "充气后鼓起来，常系在细绳上"),
    SystemQuestion("足球", QuestionDifficulty.LOW, "运动用品", "一种球类运动使用的球", "常画成黑白拼块，主要用脚踢"),
    SystemQuestion("篮球", QuestionDifficulty.LOW, "运动用品", "一种球类运动使用的球", "常为橙色，带深色曲线，要投进高处的篮筐"),
    SystemQuestion("自行车", QuestionDifficulty.LOW, "交通工具", "一种常见的交通工具", "两个轮子，通常靠人踩踏板前进"),
    SystemQuestion("飞机", QuestionDifficulty.LOW, "交通工具", "一种交通工具", "有机翼和尾翼，能在空中飞行"),
    SystemQuestion("火车", QuestionDifficulty.LOW, "交通工具", "一种载客或载货的交通工具", "许多车厢连接成列，沿铁轨行驶"),
    SystemQuestion("轮船", QuestionDifficulty.LOW, "交通工具", "一种水上交通工具", "有较大的船身和甲板，可在江海上航行"),
    SystemQuestion("大象", QuestionDifficulty.LOW, "动物", "一种体型很大的陆地动物", "长鼻子、大耳朵，常有向外伸的牙"),
    SystemQuestion("长颈鹿", QuestionDifficulty.LOW, "动物", "一种生活在草原的动物", "脖子特别长，身上有斑块，能吃高处的树叶"),
    SystemQuestion("熊猫", QuestionDifficulty.LOW, "动物", "一种黑白相间的动物", "黑眼圈和圆耳朵，常抱着竹子吃"),
    SystemQuestion("兔子", QuestionDifficulty.LOW, "动物", "一种常见的小动物", "长耳朵、短尾巴，后腿有力，常跳着走"),
    SystemQuestion("螃蟹", QuestionDifficulty.LOW, "动物", "一种水边或水里的动物", "有硬壳和一对大钳，常横着移动"),
    SystemQuestion("蝴蝶", QuestionDifficulty.LOW, "动物", "一种会飞的昆虫", "有两对宽大的翅膀，常停在花上"),
    SystemQuestion("章鱼", QuestionDifficulty.LOW, "动物", "一种海洋动物", "圆头，伸出八条柔软的腕足"),
    SystemQuestion("蜗牛", QuestionDifficulty.LOW, "动物", "一种移动缓慢的小动物", "背着螺旋形的壳，头上有触角"),
    SystemQuestion("雪人", QuestionDifficulty.LOW, "造型", "冬天常见的人形造型", "常由两个白色圆球叠成，用胡萝卜做鼻子"),
    SystemQuestion("彩虹", QuestionDifficulty.LOW, "自然现象", "一种天气过后的自然景象", "常画成天空中弯曲的多色光带"),
    SystemQuestion("骑自行车", QuestionDifficulty.MEDIUM, "动作", "一种出行活动", "人坐在两个轮子的车上，双脚踩踏板"),
    SystemQuestion("放风筝", QuestionDifficulty.MEDIUM, "动作", "一种常见的户外活动", "人牵着长线，让纸或布做的造型借风升空"),
    SystemQuestion("堆雪人", QuestionDifficulty.MEDIUM, "动作", "一种冬季活动", "把地上的白色材料滚成大球，再叠成人形"),
    SystemQuestion("钓鱼", QuestionDifficulty.MEDIUM, "动作", "一种水边的休闲活动", "人拿着长竿，细线和钩伸入水中"),
    SystemQuestion("踢足球", QuestionDifficulty.MEDIUM, "动作", "一种球类运动", "运动员用脚控制黑白球，努力送进球门"),
    SystemQuestion("打篮球", QuestionDifficulty.MEDIUM, "动作", "一种球类运动", "人运球后跳起，把橙色球投向高处的篮筐"),
    SystemQuestion("游泳", QuestionDifficulty.MEDIUM, "动作", "一种在水里进行的运动", "身体靠手臂和腿的动作向前移动"),
    SystemQuestion("滑雪", QuestionDifficulty.MEDIUM, "动作", "一种冬季运动", "脚下有长板，常拿着两根杆沿白色坡面前进"),
    SystemQuestion("跳绳", QuestionDifficulty.MEDIUM, "动作", "一种健身活动", "双手转动一根长线，身体不断跃起让它从脚下经过"),
    SystemQuestion("拔河", QuestionDifficulty.MEDIUM, "动作", "一种团体比赛", "两队人拉同一根粗绳，向相反方向使劲"),
    SystemQuestion("荡秋千", QuestionDifficulty.MEDIUM, "动作", "一种游乐活动", "人坐在绳子吊着的座板上，来回摆动"),
    SystemQuestion("刷牙", QuestionDifficulty.MEDIUM, "生活", "一种日常清洁活动", "人用带刷毛的小工具和膏状用品清洁口腔"),
    SystemQuestion("浇花", QuestionDifficulty.MEDIUM, "生活", "一种照料植物的活动", "用壶把水倒到盆栽根部或土壤中"),
    SystemQuestion("做饭", QuestionDifficulty.MEDIUM, "生活", "一种家庭活动", "人在厨房用锅和炉灶把食材变成熟食"),
    SystemQuestion("拍照", QuestionDifficulty.MEDIUM, "生活", "一种记录画面的活动", "人拿着相机，对准景物或人物按下快门"),
    SystemQuestion("弹钢琴", QuestionDifficulty.MEDIUM, "音乐", "一种演奏活动", "人坐在带黑白键的大型乐器前，用双手按键"),
    SystemQuestion("拉小提琴", QuestionDifficulty.MEDIUM, "音乐", "一种演奏活动", "把较小的弦乐器靠在肩上，用弓在弦上来回移动"),
    SystemQuestion("火山喷发", QuestionDifficulty.MEDIUM, "自然场景", "一种剧烈的自然现象", "山口向天空冒出烟尘，流出炽热的岩浆"),
    SystemQuestion("生日聚会", QuestionDifficulty.MEDIUM, "生活场景", "一种庆祝活动", "亲友围着插有蜡烛的蛋糕，庆祝一个人长大一岁"),
    SystemQuestion("海上日出", QuestionDifficulty.MEDIUM, "自然场景", "一种清晨的景象", "太阳从大片水域的地平线附近升起"),
    SystemQuestion("公园野餐", QuestionDifficulty.MEDIUM, "生活场景", "一种户外用餐活动", "人在城市绿地铺开垫子，坐在草地上分享食物"),
    SystemQuestion("猫捉老鼠", QuestionDifficulty.MEDIUM, "动物场景", "两种常见小动物之间的追逐", "有胡须的家养动物追着长尾巴的小啮齿动物"),
    SystemQuestion("蜜蜂采蜜", QuestionDifficulty.MEDIUM, "动物场景", "一种昆虫获取食物的活动", "黄黑相间的小飞虫围着花朵忙碌"),
    SystemQuestion("蜘蛛织网", QuestionDifficulty.MEDIUM, "动物场景", "一种小动物搭建捕食设施的活动", "八条腿的动物吐出细丝，做成放射状的结构"),
    SystemQuestion("猴子摘桃", QuestionDifficulty.MEDIUM, "动物场景", "一种动物在树上获取食物", "会攀爬的灵长类伸手取下毛茸茸的粉红色果实"),
    SystemQuestion("啄木鸟捉虫", QuestionDifficulty.MEDIUM, "动物场景", "一种鸟在树干上觅食", "用坚硬尖嘴敲树皮，取出藏在里面的小虫"),
    SystemQuestion("蚂蚁搬家", QuestionDifficulty.MEDIUM, "动物场景", "一群小昆虫集体迁移", "许多个体排成长队，把食物和物品运到新巢穴"),
    SystemQuestion("龟兔赛跑", QuestionDifficulty.MEDIUM, "故事场景", "一个关于耐心和骄傲的寓言", "慢的一方坚持到终点，快的一方却中途睡着了"),
    SystemQuestion("消防员灭火", QuestionDifficulty.MEDIUM, "职业场景", "一种紧急救援活动", "穿防护服的人用水管扑灭燃烧的建筑"),
    SystemQuestion("邮递员送信", QuestionDifficulty.MEDIUM, "职业场景", "一种传递信息的职业活动", "穿制服的人把写着收件人的信封送到住户门前"),
    SystemQuestion("守株待兔", QuestionDifficulty.HIGH, "成语", "比喻只想靠偶然的好运获得收获", "人坐在树桩旁，等待小动物再次撞上来"),
    SystemQuestion("画蛇添足", QuestionDifficulty.HIGH, "成语", "比喻做了多余的事，反而坏了原本的结果", "已经画好的长条爬行动物，被额外加上了腿"),
    SystemQuestion("亡羊补牢", QuestionDifficulty.HIGH, "成语", "比喻出现损失后及时修补，防止再犯", "牧人发现牲畜丢失后，把围栏破洞补好"),
    SystemQuestion("井底之蛙", QuestionDifficulty.HIGH, "成语", "比喻见识受到狭小环境的限制", "小动物待在深井里，只能看到圆形井口的一片天空"),
    SystemQuestion("刻舟求剑", QuestionDifficulty.HIGH, "成语", "比喻环境变化后仍拘泥于旧办法", "武器落进水中，人却在船边做记号寻找"),
    SystemQuestion("掩耳盗铃", QuestionDifficulty.HIGH, "成语", "比喻用自欺欺人的方式逃避事实", "人捂住自己的耳朵，伸手去偷一个会响的金属物件"),
    SystemQuestion("对牛弹琴", QuestionDifficulty.HIGH, "成语", "比喻向不能理解的人讲道理或展示技巧", "人在大型牲畜面前演奏弦乐器，对方却只顾吃草"),
    SystemQuestion("狐假虎威", QuestionDifficulty.HIGH, "成语", "比喻借用别人的威势吓唬别人", "小狐狸走在前面，身后的猛兽让其他动物逃跑"),
    SystemQuestion("盲人摸象", QuestionDifficulty.HIGH, "成语", "比喻只了解局部，就误以为了解整体", "几个人各触摸巨兽的不同部位，得出不同形状的结论"),
    SystemQuestion("杯弓蛇影", QuestionDifficulty.HIGH, "成语", "比喻把虚假的危险当真，自己吓自己", "酒杯里出现墙上弓的倒影，被误认为长条动物"),
    SystemQuestion("拔苗助长", QuestionDifficulty.HIGH, "成语", "比喻急于求成，反而破坏事物的发展", "农人把地里的小植株往上提，以为这样长得更快"),
    SystemQuestion("画龙点睛", QuestionDifficulty.HIGH, "成语", "比喻补上最关键的一笔，让整体变得生动", "画师在传说中的巨兽脸上补上眼睛"),
    SystemQuestion("叶公好龙", QuestionDifficulty.HIGH, "成语", "比喻表面喜欢某事物，真的遇到时却害怕", "家里装满神兽图案的人，见到真正的神兽却逃走"),
    SystemQuestion("愚公移山", QuestionDifficulty.HIGH, "成语", "比喻坚持不懈地克服巨大困难", "老人带着家人不断挖土，想把挡路的大山搬走"),
    SystemQuestion("精卫填海", QuestionDifficulty.HIGH, "成语", "比喻坚持不懈地完成艰难的目标", "一只小鸟不断衔来石子，投进广阔的水域"),
    SystemQuestion("夸父追日", QuestionDifficulty.HIGH, "成语", "一个追求巨大目标的神话故事", "巨人迈着大步，一直追赶天空中发光的太阳"),
    SystemQuestion("鹬蚌相争", QuestionDifficulty.HIGH, "成语", "比喻双方争执不下，让第三方得利", "长嘴水鸟与贝壳互相夹住，渔人走来把两者带走"),
    SystemQuestion("鸡飞狗跳", QuestionDifficulty.HIGH, "成语", "形容混乱而不得安宁的场面", "家禽扑着翅膀乱飞，家犬四处蹦跳"),
    SystemQuestion("狼吞虎咽", QuestionDifficulty.HIGH, "成语", "形容吃东西很急很猛", "人抱着大碗，张大嘴巴快速吃下一堆食物"),
    SystemQuestion("一箭双雕", QuestionDifficulty.HIGH, "成语", "比喻做一件事，得到两个成果", "一支射出的箭，同时命中两只大鸟"),
    SystemQuestion("水滴石穿", QuestionDifficulty.HIGH, "成语", "比喻坚持的力量可以完成看似困难的事", "水珠长时间落在同一处，把坚硬岩块打出孔洞"),
    SystemQuestion("雪中送炭", QuestionDifficulty.HIGH, "成语", "比喻在别人最困难时提供帮助", "寒冷的白色天气里，有人给冻得发抖的人送来燃料"),
    SystemQuestion("釜底抽薪", QuestionDifficulty.HIGH, "成语", "比喻从根本上解决问题", "人把煮锅下面燃烧的柴火取走，让锅不再沸腾"),
    SystemQuestion("骑虎难下", QuestionDifficulty.HIGH, "成语", "比喻事情进行后，陷入难以停止的处境", "人坐在猛兽背上，想离开又不敢落地"),
    SystemQuestion("破镜重圆", QuestionDifficulty.HIGH, "成语", "比喻分离之后重新团聚", "碎成两半的圆形镜子被重新拼合"),
    SystemQuestion("开门见山", QuestionDifficulty.HIGH, "成语", "比喻说话直接点明主题", "房门一打开，正面就出现一座大山"),
    SystemQuestion("一叶障目", QuestionDifficulty.HIGH, "成语", "比喻被小问题遮挡，看不到整体", "一片树叶挡在眼睛前，遮住了远处的景物"),
    SystemQuestion("悬崖勒马", QuestionDifficulty.HIGH, "成语", "比喻在危险边缘及时停止错误行为", "人在陡峭山崖边拉紧缰绳，让坐骑停住"),
    SystemQuestion("瓜田李下", QuestionDifficulty.HIGH, "成语", "比喻容易引起别人怀疑的处境", "人在结满圆果的地里整理鞋，又在果树下整理帽子"),
    SystemQuestion("走马观花", QuestionDifficulty.HIGH, "成语", "比喻观察得匆忙，只看表面", "人坐在奔跑的坐骑上，快速经过路边的花朵"),
)

/** Stateless selection over the game's used-word set; never silently reuses exhausted content. */
internal class SystemQuestionBank(questions: List<SystemQuestion> = SYSTEM_QUESTIONS,
    private val randomIndex: (Int) -> Int = { SecureRandom().nextInt(it) }) {
    private val entries = questions.toList()
    init {
        require(entries.isNotEmpty())
        require(entries.map { it.word }.distinct().size == entries.size) { "系统词库答案不能重复" }
        require(entries.all { q ->
            q.difficulty != QuestionDifficulty.RANDOM && q.category.isNotBlank() &&
                q.word == q.word.trim() && q.word.isNotEmpty() &&
                q.word.codePointCount(0, q.word.length) <= 32 && q.word.none(Char::isISOControl) &&
                listOf(q.hint1, q.hint2).all { hint ->
                    hint.isNotBlank() && hint.length <= 120 && hint.none(Char::isISOControl) && !hint.contains(q.word)
                }
        }) { "系统题目、难度与两条提示必须有效，提示不能直接包含答案" }
    }
    private fun candidates(difficulty: QuestionDifficulty, usedWords: Set<String>) =
        entries.filter { it.word !in usedWords && (difficulty == QuestionDifficulty.RANDOM || it.difficulty == difficulty) }

    fun remaining(difficulty: QuestionDifficulty, usedWords: Set<String>) = candidates(difficulty, usedWords).size

    fun pick(difficulty: QuestionDifficulty, usedWords: Set<String>): SystemQuestion {
        val available = candidates(difficulty, usedWords)
        require(available.isNotEmpty()) { "${difficulty.label}题库本局已用完，请返回选择其他难度或自由出题" }
        // One index across the pooled words gives every eligible word the same probability.
        val index = randomIndex(available.size)
        require(index in available.indices) { "抽题索引无效" }
        return available[index]
    }
}
