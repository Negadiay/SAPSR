package com.sapsr.backend.bot;

import com.sapsr.backend.entity.Annotation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BotMessagesTest {

    private static final String FINDINGS = """
            [{"severity": "major", "category": "format", "origin": "symbolic", "message": "Левое поле меньше 30 мм"},
             {"severity": "critical", "category": "structure", "origin": "symbolic", "message": "Нет раздела «Заключение»"},
             {"severity": "warning", "category": "format", "origin": "symbolic", "message": "Подпись рисунка без точки"},
             {"severity": "warning", "category": "content", "origin": "hybrid", "needs_teacher_review": true,
              "message": "Форматы описаны по-разному"}]""";

    @Test
    @Tag("typical")
    void studentSeesMainFormatErrorsAndThatWorkIsWithTeacher() {
        String text = BotMessages.resultForStudent("КР7_321702_Терешкова.pdf", false, FINDINGS, true, null);

        assertThat(text).startsWith("❌ <b>Ошибки в оформлении: 2</b>\nКР7_321702_Терешкова.pdf")
                .contains("• Левое поле меньше 30 мм\n• Нет раздела «Заключение»")
                .contains("Содержание: 1 замечание, сдаче не мешают.")
                .contains("срок засчитан");
    }

    @Test
    @Tag("typical")
    void passedWorkSaysContentIsClean() {
        String text = BotMessages.resultForStudent("КР7.pdf", true, "[]", true, null);

        assertThat(text).startsWith("✅ <b>Оформление без ошибок</b>")
                .contains("Оформление: без ошибок.").contains("Содержание: без замечаний.")
                .endsWith("Работа у преподавателя.");
    }

    @Test
    @Tag("typical")
    void teacherSeesStudentGroupVersionAndWhatNeedsDecision() {
        String text = BotMessages.resultForTeacher("Терешкова А.В.", "321702", "Курсовая.pdf", false, true,
                LocalDateTime.of(2026, 10, 20, 9, 15), FINDINGS, null);

        assertThat(text).startsWith("📥 <b>Новая версия работы</b>\nТерешкова А.В. · 321702\nКурсовая.pdf")
                .contains("Первая версия — 20.10.2026, 09:15")
                .contains("Оформление: ошибок — 2")
                .contains("Содержание: 1 замечание, 1 — на ваше решение");
    }

    @Test
    @Tag("typical")
    void revisionListsCommentsWithShortQuoteAndReplies() {
        List<Annotation> annotations = List.of(
                note(1, 3, "HIGHLIGHT", "Система принимает файлы в форматах DOCX и PDF и формирует отчёт",
                        "Здесь DOCX и PDF, а в требованиях только PDF.", null),
                note(2, 3, "NOTE", null, "И проверьте заключение.", 1),
                note(3, 1, "FREE_TEXT", null, "Нет списка сокращений", null),
                note(4, 9, "INK", null, null, null));

        String text = BotMessages.verdictForStudent("Гракова Наталья Викторовна", "Курсовая.pdf", false, "",
                annotations, true);

        assertThat(text).startsWith("🔄 <b>Работа возвращена на доработку</b>\nКурсовая.pdf")
                .contains("Преподаватель: Гракова Наталья Викторовна")
                .contains("<b>Комментарии:</b>\n"
                        + "1. Стр. 3 — «Система принимает файлы в форматах DOCX…»\n"
                        + "Здесь DOCX и PDF, а в требованиях только PDF.\n"
                        + "   ↳ И проверьте заключение.\n"
                        + "2. Стр. 1 — Нет списка сокращений\n"
                        + "3. Стр. 9 — <i>пометка от руки</i>\n")
                .contains("Файл с пометками — в приложении");
    }

    @Test
    @Tag("boundary")
    void longListIsCutAndRestIsInFile() {
        List<Annotation> many = new ArrayList<>();
        for (int i = 1; i <= 40; i++) many.add(note(i, i, "NOTE", null, "Комментарий номер " + i, null));

        String text = BotMessages.verdictForStudent("Гракова Н.В.", "work.pdf", false, null, many, true);

        assertThat(text).contains("15. Стр. 15 — Комментарий номер 15").doesNotContain("16. Стр. 16")
                .contains("…и ещё 25 — в файле.");
        assertThat(text.length()).isLessThan(4096);
    }

    @Test
    @Tag("negative")
    void htmlFromPeopleIsEscaped() {
        String text = BotMessages.verdictForStudent("<b>Злой</b> & Ко", "a<script>.pdf", true,
                "см. <раздел 2>", List.of(), false);

        assertThat(text).contains("&lt;b&gt;Злой&lt;/b&gt; &amp; Ко").contains("a&lt;script&gt;.pdf")
                .contains("см. &lt;раздел 2&gt;").startsWith("✅ <b>Работа принята</b>");
    }

    @Test
    @Tag("boundary")
    void shortQuoteCutsAfterFewWordsOrLongWord() {
        assertThat(BotMessages.shortQuote("Коротко")).isEqualTo("Коротко");
        assertThat(BotMessages.shortQuote("один два три четыре пять шесть семь")).isEqualTo("один два три четыре пять шесть…");
        assertThat(BotMessages.shortQuote("x".repeat(100))).isEqualTo("x".repeat(60) + "…");
        assertThat(BotMessages.shortQuote("  ")).isNull();
    }

    @Test
    @Tag("negative")
    void brokenReportStillGivesMessage() {
        assertThat(BotMessages.resultForStudent("a.pdf", true, "не JSON", false, null)).contains("Оформление: без ошибок.");
    }

    @Test
    @Tag("boundary")
    void countAgreesWithNumber() {
        assertThat(BotMessages.count(1, "замечание", "замечания", "замечаний")).isEqualTo("1 замечание");
        assertThat(BotMessages.count(3, "замечание", "замечания", "замечаний")).isEqualTo("3 замечания");
        assertThat(BotMessages.count(11, "замечание", "замечания", "замечаний")).isEqualTo("11 замечаний");
        assertThat(BotMessages.count(21, "замечание", "замечания", "замечаний")).isEqualTo("21 замечание");
    }

    // ── две фазы результата (задача 5.2) ─────────────────────────────────────────────────────

    @Test
    @Tag("typical")
    void formatResultSaysContentWillAppearInSameMessage() {
        String passed = BotMessages.checkingForStudent("КР7.pdf", true, "[]");
        assertThat(passed).contains("Содержание проверяется — итог появится в этом сообщении.")
                .doesNotContain("Работа у преподавателя");

        String rejected = BotMessages.checkingForStudent("КР7.pdf", false, FINDINGS);
        assertThat(rejected).contains("Содержание проверяется").contains("срок засчитан")
                .doesNotContain("Содержание: 1");
    }

    @Test
    @Tag("negative")
    void failureMessageEscapesText() {
        String text = BotMessages.failedForStudent("<b>злой</b>.pdf", "Файл <script> не читается");
        assertThat(text).startsWith("⚠️ <b>Работу не удалось проверить</b>")
                .contains("&lt;b&gt;злой&lt;/b&gt;.pdf").contains("Файл &lt;script&gt; не читается")
                .contains("преподавателю она не отправлена.");
    }

    @Test
    @Tag("boundary")
    void teacherAndStudentSeeWhyContentIsMissing() {
        assertThat(BotMessages.resultForTeacher("Терешкова А.В.", null, "КР7.pdf", true, false, null, FINDINGS,
                "не хватило времени"))
                .contains("Содержание: не проверено (не хватило времени)").doesNotContain("на ваше решение");
        assertThat(BotMessages.resultForStudent("КР7.pdf", true, "[]", true, "не хватило времени"))
                .contains("Содержание: не проверено (не хватило времени).");
    }

    @Test
    @Tag("typical")
    void commandsDependOnRole() {
        assertThat(BotMessages.welcome("TEACHER")).contains("уже проверенными");
        assertThat(BotMessages.help("STUDENT")).contains("«Сдать работу»");
        assertThat(BotMessages.help(null)).contains("выберите роль");
    }

    private static Annotation note(int id, int page, String kind, String quote, String comment, Integer replyTo) {
        Annotation a = new Annotation();
        a.setId(id);
        a.setPage(page);
        a.setKind(kind);
        a.setQuotedText(quote);
        a.setComment(comment);
        a.setReplyToId(replyTo);
        return a;
    }
}
