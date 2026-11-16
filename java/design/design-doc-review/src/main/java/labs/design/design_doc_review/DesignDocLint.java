package labs.design.design_doc_review;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 评审之前，先让程序挑一遍方案文档的形式问题：缺哪一节、非目标是不是空的、放弃的方案有没有写输在哪……
 * 形式过了，评审人的时间才花在内容上。
 * 运行：mvn -q -pl design/design-doc-review compile exec:java -Dexec.mainClass=labs.design.design_doc_review.DesignDocLint
 *       -Dexec.args="design/design-doc-review/docs/design-doc-v1.md design/design-doc-review/docs/design-doc.md"
 */
public class DesignDocLint {
    static final String[] SECTIONS = {"1 背景和问题", "2 目标和非目标", "3 现状", "4 方案", "5 放弃的方案", "6 风险和降级", "7 上线、灰度、回滚和老数据", "8 排期和待定问题"};
    static final int MAX_LINES = 200;

    public static void main(String[] args) throws IOException {
        for (String file : args) {
            List<String> lines = Files.readAllLines(Path.of(file));
            List<String> problems = check(lines);
            System.out.println((problems.isEmpty() ? "✓ " : "✗ ") + Path.of(file).getFileName() + "（" + lines.size() + " 行）");
            problems.forEach(p -> System.out.println("    - " + p));
        }
    }

    static List<String> check(List<String> lines) {
        List<String> problems = new ArrayList<>();
        for (String s : SECTIONS) if (!lines.contains("## " + s)) problems.add("缺“## " + s + "”一节");

        List<String> goals = bullets(sub(lines, "### 目标"));
        if (goals.isEmpty()) problems.add("没有写目标");
        else if (goals.stream().noneMatch(g -> g.matches(".*\\d.*"))) problems.add("目标里没有一个数：做完了拿什么判断算不算达到");

        if (bullets(sub(lines, "### 非目标")).isEmpty()) problems.add("非目标是空的：这次明确不做什么，至少写一条");

        for (String b : bullets(sub(lines, "## 5 放弃的方案")))
            if (!b.contains("输在")) problems.add("放弃的方案没写输在哪：" + b);

        List<String> risks = tableRows(sub(lines, "## 6 风险和降级"));
        if (risks.isEmpty()) problems.add("风险一节没有表");
        else if (!risks.get(0).contains("谁定的")) problems.add("风险表缺“谁定的”一列：出事时怎么对待用户，是业务决定，要写明谁拍的板");

        for (String s : new String[]{"### 灰度", "### 回滚", "### 老数据"})
            if (String.join("", sub(lines, s)).isBlank()) problems.add("第 7 节缺“" + s + "”：" + switch (s) {
                case "### 灰度" -> "先给谁用、看什么指标";
                case "### 回滚" -> "出事了怎么回去、多久能回去";
                default -> "上线前已有的数据怎么办";
            });

        List<String> open = sub(lines, "### 待定问题");
        List<String> rows = tableRows(open);
        if (rows.isEmpty()) problems.add("待定问题不是一张表" + (String.join("", open).contains("无") ? "，写的是“无”：评审前一个待定的都没有，多半是没列出来" : ""));
        else if (!rows.get(0).contains("负责人") || !rows.get(0).contains("期限")) problems.add("待定问题表要有“负责人”和“期限”两列");

        if (lines.size() > MAX_LINES) problems.add("有 " + lines.size() + " 行，超过 " + MAX_LINES + " 行：太长就没人读完，细节放附录或链接");
        return problems;
    }

    /** 取出某个标题下面、到下一个同级或更高级标题之前的行。标题不存在返回空列表。 */
    static List<String> sub(List<String> lines, String heading) {
        int start = lines.indexOf(heading);
        List<String> out = new ArrayList<>();
        if (start < 0) return out;
        int level = heading.indexOf(' ');
        for (int i = start + 1; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.startsWith("#") && l.indexOf(' ') <= level) break;
            out.add(l);
        }
        return out;
    }

    static List<String> bullets(List<String> body) {
        return body.stream().filter(l -> l.startsWith("- ")).map(l -> l.substring(2)).toList();
    }

    /** 表格的行（含表头，不含分隔线）。 */
    static List<String> tableRows(List<String> body) {
        return body.stream().filter(l -> l.startsWith("|") && !l.startsWith("|---")).toList();
    }
}
