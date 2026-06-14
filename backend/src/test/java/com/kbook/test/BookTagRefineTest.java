package com.kbook.test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.common.util.CommonUtils;
import com.kbook.config.ChatModelFactory;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.book.BookParserService;
import com.kbook.service.book.BookService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@SpringBootTest
@ActiveProfiles("dev")
public class BookTagRefineTest {

    private static final String VALID_TAGS =
            """
                    1. 体裁与文学形式（小说/非小说）
                    长篇小说、短篇小说、推理、悬疑、科幻、奇幻、武侠、言情、官场、穿越、重生、仙侠、修真、玄幻、都市、青春校园、乡土、历史小说、军事小说、谍战、惊悚、恐怖、灵异、轻小说、浪漫、史诗、悲剧、喜剧、意识流、黑色幽默、讽刺、现实主义、魔幻现实主义、传记、回忆录、纪实、随笔、散文、诗歌、书信、寓言、童话、神话、传说、民间故事、绘本、漫画、连环画、戏剧、戏曲、网络小说、同人、种田、后宫、系统
                    
                    2. 内容题材与领域（核心题材）
                    历史、中国历史、世界历史、古代史、中世纪史、近代史、现代史、战争、二战、冷战、抗战、解放战争、革命、政治、权力、权谋、宫廷、官场、黑帮、犯罪、间谍、法律、刑侦、哲学、伦理学、美学、逻辑学、存在主义、心理学、社会学、人类学、文化、艺术、音乐、电影、摄影、建筑、设计、经济、金融、投资、理财、商业、创业、管理、市场营销、广告、品牌、职场、自我提升、个人成长、时间管理、沟通、谈判、演讲、人际关系、婚姻、家庭、亲子、育儿、教育、学习、阅读、外语、英语、科普、科学、物理、数学、化学、生物、医学、健康、中医、营养、饮食、烹饪、运动、健身、瑜伽、旅行、地理、自然、环境保护、宗教、神话、民俗、国学、儒学、道家、佛学、禅宗、基督教、伊斯兰教、军事、战略、武器
                    
                    3. 主题与母题（核心情感/思想）
                    爱情、友情、亲情、成长、复仇、救赎、孤独、死亡、欲望、梦想、自由、正义、背叛、生存、冒险、悬疑、秘密、身份、记忆、命运、选择、贫穷、财富、权力斗争、阶级、压迫、反抗、希望、绝望、勇气、恐惧、治愈、疗愈、存在、虚无、生命、时间、人性、伦理、道德
                    
                    4. 读者与人物视角（目标群体/主角特征）
                    女性、男性、青少年、儿童、中年、老年、学生、职场人、创业者、管理者、父母、情侣、单身、女性主角、男性主角、群体视角、个人视角
                    
                    5. 时代与地域（时空背景）
                    中国、美国、英国、法国、德国、俄国/苏联、日本、韩国、印度、欧洲、非洲、中东、古代、先秦、秦汉、三国、晋、南北朝、隋、唐、五代十国、宋、辽、金、元、明、清、民国、当代、未来、异世界、架空
                    
                    6. 风格与氛围（阅读感受）
                    治愈、幽默、轻松、热血、虐恋、甜宠、温情、暗黑、唯美、搞笑、沙雕、冷峻、诗意、悲情、爽文、悬疑、惊悚、恐怖
                    
                    7. 特殊或专业领域（可检索的专业/学科标签）
                    计算机、编程、人工智能、数据科学、网络安全、软件工程、互联网、产品、设计、交互、通信、电子、机械、航天、能源、材料、农业、动物、植物、语言学、考古、地质、天文、气象、环境科学、交通、土木、生物工程、医学、药学、护理、心理学、精神病学、法律、政治学、经济学、管理学、社会学、人类学、哲学、历史学、教育学、新闻传播、图书馆学、艺术学、体育学
                    """;
    private static final String VALID_TAGS_STRING =
            "长篇小说,短篇小说,推理,悬疑,科幻,奇幻,武侠,言情,官场,穿越,重生,仙侠,修真,玄幻,都市,青春校园,乡土,历史小说,军事小说,谍战,惊悚,恐怖,灵异,轻小说,浪漫,史诗,悲剧,喜剧,意识流,黑色幽默,讽刺,现实主义,魔幻现实主义,传记,回忆录,纪实,随笔,散文,诗歌,书信,寓言,童话,神话,传说,民间故事,绘本,漫画,连环画,戏剧,戏曲,网络小说,同人,种田,后宫,系统," +
            "历史,中国历史,世界历史,古代史,中世纪史,近代史,现代史,战争,二战,冷战,抗战,解放战争,革命,政治,权力,权谋,宫廷,官场,黑帮,犯罪,间谍,法律,刑侦,哲学,伦理学,美学,逻辑学,存在主义,心理学,社会学,人类学,文化,艺术,音乐,电影,摄影,建筑,设计,经济,金融,投资,理财,商业,创业,管理,市场营销,广告,品牌,职场,自我提升,个人成长,时间管理,沟通,谈判,演讲,人际关系,婚姻,家庭,亲子,育儿,教育,学习,阅读,外语,英语,科普,科学,物理,数学,化学,生物,医学,健康,中医,营养,饮食,烹饪,运动,健身,瑜伽,旅行,地理,自然,环境保护,宗教,神话,民俗,国学,儒学,道家,佛学,禅宗,基督教,伊斯兰教,军事,战略,武器," +
            "爱情,友情,亲情,成长,复仇,救赎,孤独,死亡,欲望,梦想,自由,正义,背叛,生存,冒险,悬疑,秘密,身份,记忆,命运,选择,贫穷,财富,权力斗争,阶级,压迫,反抗,希望,绝望,勇气,恐惧,治愈,疗愈,存在,虚无,生命,时间,人性,伦理,道德," +
            "女性,男性,青少年,儿童,中年,老年,学生,职场人,创业者,管理者,父母,情侣,单身,女性主角,男性主角,群体视角,个人视角," +
            "中国,美国,英国,法国,德国,俄国/苏联,日本,韩国,印度,欧洲,非洲,中东,古代,先秦,秦汉,三国,晋,南北朝,隋,唐,五代十国,宋,辽,金,元,明,清,民国,当代,未来,异世界,架空," +
            "治愈,幽默,轻松,热血,虐恋,甜宠,温情,暗黑,唯美,搞笑,沙雕,冷峻,诗意,悲情,爽文,悬疑,惊悚,恐怖," +
            "计算机,编程,人工智能,数据科学,网络安全,软件工程,互联网,产品,设计,交互,通信,电子,机械,航天,能源,材料,农业,动物,植物,语言学,考古,地质,天文,气象,环境科学,交通,土木,生物工程,医学,药学,护理,心理学,精神病学,法律,政治学,经济学,管理学,社会学,人类学,哲学,历史学,教育学,新闻传播,图书馆学,艺术学,体育学";



    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private BookService bookService;

    @Autowired
    private ChatModelFactory chatModelFactory;
    @Autowired
    private BookParserService bookParserService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    public void refineBookTags() {
        ChatModel chatModel = chatModelFactory.buildToolChatModel();
        if (chatModel == null) {
            System.err.println("AI 模型不可用，跳过测试");
            return;
        }

        List<Book> allBooks = bookRepository.findAll();
        System.out.println("共 " + allBooks.size() + " 本图书需要处理");
        System.out.println("合法标签总数: " + VALID_TAGS_STRING.split(",").length);

        String systemPrompt = """
                你是一个图书标签整理专家。请根据图书信息，从下面提供的标签集合中，为图书选择最合适的标签（3-8个）。
                请严格按照以下要求：
                1. 只从下面提供的标签集合中选择，不要创造新标签
                2. 返回格式为JSON数组，如：["标签1","标签2","标签3"]
                3. 确保标签与图书内容高度相关
                4. 选择的标签要覆盖不同维度（体裁与文学形式、内容题材与领域、主题与母题、读者与人物视角、时代与地域、风格与氛围, 特殊或专业领域可根据情况选择）
                
                可用标签：
                """ + VALID_TAGS;

        int successCount = 0;
        int failCount = 0;
        int totalAiCalls = 0;
        long totalElapsed = 0;
        int totalInputTokens = 0;
        int totalOutputTokens = 0;

        for (int i = 40; i < allBooks.size(); i++) {
            Book book = allBooks.get(i);
            try {
                String oldTagsDisplay = parseTagsForDisplay(book.getFormatTags());
                System.out.println("处理第 " + (i + 1) + "/" + allBooks.size() + " 本: [" + book.getId() + "] " + book.getTitle());
                System.out.println("  旧标签: " + oldTagsDisplay);

                String existingTags = book.getFormatTags() != null ? book.getFormatTags() : "[]";

                String userPrompt = String.format("""
                        图书信息：
                        【书名】：%s
                        【作者】：%s
                        【现有标签】：%s
                        【简介】：%s
                        【摘要】: %s

                        请从可用标签中选择最合适的3-8个标签，以JSON数组格式返回。
                        """,
                        book.getTitle() != null ? book.getTitle() : "",
                        book.getAuthor() != null ? book.getAuthor() : "",
                        existingTags,
                        book.getDescription() != null ? book.getDescription() : "",
                        book.getChapterSummary() != null ? book.getChapterSummary() : ""
                );

                long startTime = System.currentTimeMillis();
                ChatResponse response = chatModel.chat(List.of(
                        SystemMessage.from(systemPrompt),
                        UserMessage.from(userPrompt)
                ));
                long elapsed = System.currentTimeMillis() - startTime;

                String result = response.aiMessage().text().trim();

                int jsonStart = result.indexOf('[');
                int jsonEnd = result.lastIndexOf(']');
                if (jsonStart < 0 || jsonEnd <= jsonStart) {
                    System.err.println("  ✗ 无法解析AI返回结果，跳过");
                    failCount++;
                    continue;
                }

                String jsonStr = result.substring(jsonStart, jsonEnd + 1);
                List<String> tags = objectMapper.readValue(jsonStr, new TypeReference<>() {
                });
                List<String> validTagList = Arrays.asList(VALID_TAGS_STRING.split(","));
                List<String> refinedTags = tags.stream()
                        .map(String::trim)
                        .filter(validTagList::contains)
                        .collect(Collectors.toList());

                if (refinedTags.isEmpty()) {
                    System.err.println("  ✗ AI返回标签均不在合法集合中，跳过更新");
                    failCount++;
                    continue;
                }

                String newTagsDisplay = refinedTags.toString();
                System.out.println("  新标签: " + newTagsDisplay);
                String oldRaw = book.getFormatTags();
                String newRaw = refinedTags.stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(",", "[", "]"));
                if (!oldTagsDisplay.equals(newTagsDisplay)) {
                    System.err.println("  差异: " + diffTags(oldRaw, newRaw));
                }

                int inputTokens = CommonUtils.estimateTokens(systemPrompt + userPrompt);
                int outputTokens = CommonUtils.estimateTokens(result);
                CommonUtils.logAiCall("标签精炼", elapsed, inputTokens, outputTokens,
                        "[" + book.getId() + "] " + book.getTitle() + " | " + oldTagsDisplay + " → " + newTagsDisplay);

                bookService.updateFormatTags(book.getId(), refinedTags);
                bookParserService.generateBookEmbedding(book);
                successCount++;
                totalAiCalls++;
                totalElapsed += elapsed;
                totalInputTokens += inputTokens;
                totalOutputTokens += outputTokens;

            } catch (Exception e) {
                failCount++;
                System.err.println("  ✗ 失败: " + book.getTitle() + " - " + e.getMessage());
            }
        }

        System.out.println("\n========== 处理完成 ==========");
        System.out.println("总数: " + allBooks.size());
        System.out.println("成功: " + successCount);
        System.out.println("失败: " + failCount);
        if (totalAiCalls > 0) {
            System.out.println("AI 调用统计: " + totalAiCalls + " 次, 总耗时 " + totalElapsed + "ms, "
                    + "平均 " + (totalElapsed / totalAiCalls) + "ms/次, "
                    + "总输入 " + totalInputTokens + " tokens, 总输出 " + totalOutputTokens + " tokens");
        }
    }

    private String parseTagsForDisplay(String formatTags) {
        try {
            if (formatTags == null || formatTags.isBlank()) return "[]";
            List<String> list = objectMapper.readValue(formatTags, new TypeReference<>() {
            });
            return list.toString();
        } catch (Exception e) {
            return formatTags;
        }
    }

    private String diffTags(String oldJson, String newJson) {
        try {
            List<String> oldList = objectMapper.readValue(oldJson, new TypeReference<>() {
            });
            List<String> newList = objectMapper.readValue(newJson, new TypeReference<>() {
            });
            List<String> added = newList.stream().filter(t -> !oldList.contains(t)).toList();
            List<String> removed = oldList.stream().filter(t -> !newList.contains(t)).toList();
            StringBuilder sb = new StringBuilder();
            if (!added.isEmpty()) sb.append("+ ").append(added);
            if (!removed.isEmpty()) {
                if (!sb.isEmpty()) sb.append(" | ");
                sb.append("- ").append(removed);
            }
            return sb.isEmpty() ? "无变化" : sb.toString();
        } catch (Exception e) {
            return "对比失败";
        }
    }
}
