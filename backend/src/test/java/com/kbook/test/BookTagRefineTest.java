package com.kbook.test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbook.entity.Book;
import com.kbook.repository.BookRepository;
import com.kbook.service.AiProviderConfigService;
import com.kbook.service.BookParserService;
import com.kbook.service.BookService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.stream.Stream;

@SpringBootTest
@ActiveProfiles("test")
public class BookTagRefineTest {

    private static final String VALID_TAGS =
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
    private AiProviderConfigService aiProviderConfigService;
    @Autowired
    private BookParserService bookParserService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    public void refineBookTags() {
        ChatModel chatModel = aiProviderConfigService.buildTagChatModel();
        if (chatModel == null) {
            System.err.println("AI 模型不可用，跳过测试");
            return;
        }

        List<Book> allBooks = bookRepository.findAll();
        System.out.println("共 " + allBooks.size() + " 本图书需要处理");

        String systemPrompt = """
                你是一个图书标签整理专家。请根据图书信息，从下面提供的标签集合中，为图书选择最合适的标签（3-8个）。
                请严格按照以下要求：
                1. 只从下面提供的标签集合中选择，不要创造新标签
                2. 返回格式为JSON数组，如：["标签1","标签2","标签3"]
                3. 确保标签与图书内容高度相关
                4. 选择的标签要覆盖不同维度（体裁、题材、风格等）
                
                可用标签：
                """ + VALID_TAGS;

        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < allBooks.size(); i++) {
            Book book = allBooks.get(i);
            try {
                System.out.println("处理第 " + (i + 1) + "/" + allBooks.size() + " 本: [" + book.getId() + "] " + book.getTitle());

                String existingTags = book.getFormatTags() != null ? book.getFormatTags() : "[]";

                String userPrompt = String.format("""
                        图书信息：
                        书名：%s
                        作者：%s
                        现有标签：%s
                        简介：%s
                        
                        请从可用标签中选择最合适的3-8个标签，以JSON数组格式返回。
                        """,
                        book.getTitle() != null ? book.getTitle() : "",
                        book.getAuthor() != null ? book.getAuthor() : "",
                        existingTags,
                        book.getDescription() != null ? book.getDescription() : ""
                );

                ChatResponse response = chatModel.chat(List.of(
                        SystemMessage.from(systemPrompt),
                        UserMessage.from(userPrompt)
                ));

                String result = response.aiMessage().text().trim();
                System.out.println("  AI 返回: " + result);

                int jsonStart = result.indexOf('[');
                int jsonEnd = result.lastIndexOf(']');
                if (jsonStart < 0 || jsonEnd <= jsonStart) {
                    System.err.println("  ✗ 无法解析AI返回结果，跳过");
                    failCount++;
                    continue;
                }

                String jsonStr = result.substring(jsonStart, jsonEnd + 1);
                List<String> tags = objectMapper.readValue(jsonStr, new TypeReference<List<String>>() {});
                List<String> validTags = Stream.of(VALID_TAGS.split(","))
                        .map(String::trim)
                        .toList();
                List<String> refinedTags = tags.stream()
                        .filter(t -> validTags.contains(t))
                        .toList();

                if (refinedTags.isEmpty()) {
                    System.err.println("  ✗ 过滤后无有效标签，跳过更新");
                    failCount++;
                    continue;
                }

                bookService.updateFormatTags(book.getId(), refinedTags);
                bookParserService.generateBookEmbedding(book);
                successCount++;
                System.out.println("  ✓ 更新标签: " + refinedTags);

            } catch (Exception e) {
                failCount++;
                System.err.println("  ✗ 失败: " + book.getTitle() + " - " + e.getMessage());
            }
        }

        System.out.println("\n========== 处理完成 ==========");
        System.out.println("总数: " + allBooks.size());
        System.out.println("成功: " + successCount);
        System.out.println("失败: " + failCount);
    }
}
