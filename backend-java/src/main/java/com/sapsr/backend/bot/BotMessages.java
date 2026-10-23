package com.sapsr.backend.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapsr.backend.entity.Annotation;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Тексты сообщений бота — HTML-разметка Telegram (parse_mode=HTML). Всё, что пришло от людей
 * (имена, названия файлов, комментарии, тексты замечаний), экранируется.
 */
public final class BotMessages {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    /** Сколько главных ошибок оформления показать студенту прямо в сообщении. */
    private static final int TOP_FORMAT_ERRORS = 3;
    /** Сколько комментариев из файла преподавателя показать в сообщении; остальные — в файле. */
    private static final int MAX_COMMENTS = 15;
    private static final int MAX_COMMENT_CHARS = 300;
    private static final int QUOTE_WORDS = 6;
    private static final int QUOTE_CHARS = 60;
    /** Запас до предела Telegram в 4096 символов. */
    private static final int MESSAGE_BUDGET = 3800;

    private BotMessages() {}

    // ── приветствие и справка ──────────────────────────────────────────────

    public static String welcome() {
        return """
                <b>SAPSR — проверка пояснительных записок</b>

                <b>Студенту:</b> загрузите работу в приложении. Она пройдёт автоматическую проверку оформления \
                и содержания и сразу уйдёт преподавателю.
                <b>Преподавателю:</b> работы студентов, отчёты проверки и возврат работы с пометками — там же.

                Сюда будут приходить уведомления: итог проверки, решение преподавателя и его комментарии.
                Открыть приложение — кнопкой ниже или кнопкой «Открыть» слева от поля ввода.""";
    }

    public static String help() {
        return """
                <b>Как пользоваться</b>

                1. Откройте приложение кнопкой ниже.
                2. Студент выбирает преподавателя и вид работы и загружает PDF — семестр и этап опроцентовки \
                приложение определяет само.
                3. Через несколько минут здесь появится итог проверки, а работа — у преподавателя.
                4. Преподаватель принимает работу или возвращает её с пометками в файле — комментарии придут сюда же.

                /start — открыть приложение
                /help — эта справка""";
    }

    public static String unknownMessage() {
        return "Я присылаю уведомления о проверке работ, а сами работы — в приложении. "
                + "Откройте его кнопкой ниже. Справка — /help.";
    }

    public static String startupNotice() {
        return "🔄 <b>SAPSR снова работает.</b> Открыть приложение — кнопкой ниже.";
    }

    // ── итог автоматической проверки ──────────────────────────────────────

    /** Счётчики по результату обработчика (format_errors, формат v1). */
    record Findings(int formatErrors, int formatWarnings, int content, int needsReview, List<String> topErrors) {}

    static Findings findings(String errorsJson) {
        int errors = 0, warnings = 0, content = 0, review = 0;
        List<String> top = new ArrayList<>();
        try {
            JsonNode list = JSON.readTree(errorsJson == null ? "[]" : errorsJson);
            if (list.isArray()) {
                for (JsonNode f : list) {
                    String category = f.path("category").asText("");
                    String origin = f.path("origin").asText("symbolic");
                    if ("content".equals(category) || "requirements".equals(category) || !"symbolic".equals(origin)) {
                        content++;
                        if (f.path("needs_teacher_review").asBoolean(false)) review++;
                        continue;
                    }
                    String severity = f.path("severity").asText("");
                    if ("critical".equals(severity) || "major".equals(severity)) {
                        errors++;
                        String message = f.path("message").asText("");
                        if (!message.isBlank() && top.size() < TOP_FORMAT_ERRORS) top.add(message);
                    } else {
                        warnings++;
                    }
                }
            }
        } catch (Exception e) {
            // Повреждённый отчёт — сообщение без счётчиков лучше, чем никакого.
        }
        return new Findings(errors, warnings, content, review, top);
    }

    public static String resultForStudent(String fileName, boolean passed, String errorsJson) {
        return resultForStudent(fileName, passed, errorsJson, false);
    }

    /**
     * Итог проверки оформления. {@code contentPending} — содержание ещё проверяется (фаза format протокола v2):
     * итог по содержанию придёт вторым сообщением, а прошедшая оформление работа уйдёт преподавателю после него.
     */
    public static String resultForStudent(String fileName, boolean passed, String errorsJson, boolean contentPending) {
        Findings f = findings(errorsJson);
        StringBuilder text = new StringBuilder(passed
                ? "✅ <b>Работа прошла проверку оформления</b>\n"
                : "❌ <b>В работе есть ошибки оформления</b>\n");
        text.append("📄 ").append(escape(fileName)).append("\n\n");
        if (passed) {
            text.append(f.formatWarnings() > 0
                    ? "Оформление: предупреждений — " + f.formatWarnings() + ".\n"
                    : "Оформление: замечаний нет.\n");
        } else {
            text.append("Оформление: ошибок — ").append(f.formatErrors())
                    .append(f.formatWarnings() > 0 ? ", предупреждений — " + f.formatWarnings() : "").append(".\n");
            for (String message : f.topErrors()) text.append("• ").append(escape(message)).append('\n');
            int hidden = f.formatErrors() - f.topErrors().size();
            if (hidden > 0) text.append("• и ещё ").append(hidden).append(" — в отчёте\n");
        }
        if (contentPending) {
            text.append("Содержание ещё проверяется — итог придёт отдельным сообщением.\n");
        } else if (f.content() > 0) {
            text.append("Содержание: замечаний — ").append(f.content())
                    .append(". Они сдаче не мешают.\n");
        }
        text.append('\n');
        if (passed) {
            text.append(contentPending
                    ? "Работа уйдёт преподавателю, как только закончится проверка содержания."
                    : "Работа передана преподавателю. Полный отчёт — в приложении.");
        } else {
            // Решение 9: преподаватель получает и отклонённую работу — срок сдачи засчитан.
            text.append("Работа всё равно у преподавателя — срок сдачи засчитан. Исправьте ошибки и загрузите "
                    + "новую версию: она заменит эту. Полный отчёт — в приложении.");
        }
        return text.toString();
    }

    /** Итог содержательной проверки — второе сообщение после итога оформления. */
    public static String contentForStudent(String fileName, boolean passed, String errorsJson) {
        Findings f = findings(errorsJson);
        StringBuilder text = new StringBuilder("🧠 <b>Проверка содержания закончена</b>\n");
        text.append("📄 ").append(escape(fileName)).append("\n\n");
        if (f.content() > 0) {
            text.append("Содержание: замечаний — ").append(f.content());
            if (f.needsReview() > 0) text.append(", из них на проверку преподавателю — ").append(f.needsReview());
            text.append(". Сдаче они не мешают.\n");
        } else {
            text.append("Содержание: замечаний нет.\n");
        }
        text.append('\n').append(passed
                ? "Работа передана преподавателю. Полный отчёт — в приложении."
                : "Отчёт у преподавателя дополнен. Полный отчёт — в приложении.");
        return text.toString();
    }

    /** Содержательная проверка не успела за тайм-аут или не удалась — работа уходит с отчётом по оформлению. */
    public static String contentIncompleteForStudent(String fileName, boolean passed, String reason) {
        StringBuilder text = new StringBuilder("⚠️ <b>Содержание проверить не удалось</b>\n");
        text.append("📄 ").append(escape(fileName)).append("\n\n");
        text.append(escape(reason)).append(" В отчёте — только оформление.\n\n");
        text.append(passed
                ? "Работа передана преподавателю."
                : "Работа у преподавателя вместе с ошибками оформления.");
        return text.toString();
    }

    /** Проверка не состоялась: файл не читается, зашифрован, обработчик не ответил. */
    public static String failedForStudent(String fileName, String message) {
        return "⚠️ <b>Проверка не удалась</b>\n"
                + "📄 " + escape(fileName) + "\n\n"
                + escape(message) + "\n\n"
                + "Загрузите работу снова. Преподавателю она не отправлена.";
    }

    public static String resultForTeacher(String studentName, String fileName, boolean passed, boolean newVersion,
                                          LocalDateTime firstSubmittedAt, String errorsJson) {
        return resultForTeacher(studentName, fileName, passed, newVersion, firstSubmittedAt, errorsJson, null);
    }

    /** {@code contentNote} — почему в отчёте нет содержательной части (тайм-аут, сбой); null — отчёт полный. */
    public static String resultForTeacher(String studentName, String fileName, boolean passed, boolean newVersion,
                                          LocalDateTime firstSubmittedAt, String errorsJson, String contentNote) {
        Findings f = findings(errorsJson);
        StringBuilder text = new StringBuilder(newVersion ? "📥 <b>Новая версия работы</b>\n" : "📥 <b>Новая работа</b>\n");
        text.append("👤 ").append(escape(studentName)).append('\n');
        text.append("📄 ").append(escape(fileName)).append('\n');
        if (newVersion && firstSubmittedAt != null) {
            text.append("Первая версия пришла ").append(DATE_TIME.format(firstSubmittedAt)).append(".\n");
        }
        text.append('\n').append(passed ? "Оформление: ✅ без ошибок" : "Оформление: ❌ ошибок — " + f.formatErrors());
        text.append('\n');
        if (contentNote != null) {
            text.append("Содержание: ⚠️ ").append(escape(contentNote)).append('\n');
        } else if (f.content() > 0) {
            text.append("Содержание: замечаний — ").append(f.content());
            if (f.needsReview() > 0) text.append(", на вашу проверку — ").append(f.needsReview());
            text.append('\n');
        }
        text.append("\nОткройте кабинет, чтобы посмотреть отчёт и вынести решение.");
        return text.toString();
    }

    // ── решение преподавателя ─────────────────────────────────────────────

    /**
     * Решение по работе. Комментарии из файла с пометками перечисляются прямо в сообщении — студент видит их сразу,
     * а файл понадобится, когда он сядет исправлять работу.
     */
    public static String verdictForStudent(String teacherName, String fileName, boolean approved, String comment,
                                           List<Annotation> annotations, boolean hasReviewFile) {
        StringBuilder text = new StringBuilder(approved
                ? "✅ <b>Работа принята</b>\n"
                : "🔄 <b>Работа отправлена на доработку</b>\n");
        text.append("👤 Преподаватель: ").append(escape(teacherName)).append('\n');
        text.append("📄 ").append(escape(fileName)).append('\n');
        if (comment != null && !comment.isBlank()) {
            text.append("\n💬 ").append(escape(cut(comment.strip(), MAX_COMMENT_CHARS))).append('\n');
        }
        if (annotations != null && !annotations.isEmpty()) {
            text.append("\n<b>Комментарии в файле работы:</b>\n");
            appendComments(text, annotations);
            text.append("\nФайл с пометками — в приложении, в «Моих работах».");
        } else if (hasReviewFile) {
            text.append("\nФайл работы с пометками преподавателя — в приложении, в «Моих работах».");
        }
        return text.toString();
    }

    // Нумеруются комментарии верхнего уровня; ответы — строкой «↳» под своим комментарием.
    private static void appendComments(StringBuilder text, List<Annotation> annotations) {
        Map<Integer, List<Annotation>> replies = new HashMap<>();
        List<Annotation> roots = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        for (Annotation a : annotations) ids.add(a.getId());
        for (Annotation a : annotations) {
            if (a.getReplyToId() != null && ids.contains(a.getReplyToId())) {
                replies.computeIfAbsent(a.getReplyToId(), k -> new ArrayList<>()).add(a);
            } else {
                roots.add(a);
            }
        }
        int number = 0;
        for (Annotation root : roots) {
            StringBuilder item = new StringBuilder();
            item.append(number + 1).append(". Стр. ").append(root.getPage());
            String quote = shortQuote(root.getQuotedText());
            String comment = root.getComment() == null ? null : cut(root.getComment().strip(), MAX_COMMENT_CHARS);
            if (quote != null) {
                item.append(" — «").append(escape(quote)).append("»\n");
                item.append(comment != null ? escape(comment) : "<i>выделено без комментария</i>").append('\n');
            } else {
                item.append(" — ").append(comment != null ? escape(comment) : "<i>" + markLabel(root.getKind()) + "</i>").append('\n');
            }
            for (Annotation reply : replies.getOrDefault(root.getId(), List.of())) {
                if (reply.getComment() == null || reply.getComment().isBlank()) continue;
                item.append("   ↳ ").append(escape(cut(reply.getComment().strip(), MAX_COMMENT_CHARS))).append('\n');
            }
            if (number >= MAX_COMMENTS || text.length() + item.length() > MESSAGE_BUDGET) {
                text.append("…и ещё ").append(roots.size() - number).append(" — в файле работы.\n");
                return;
            }
            text.append(item);
            number++;
        }
    }

    private static String markLabel(String kind) {
        return switch (kind == null ? "" : kind) {
            case "INK" -> "пометка от руки";
            case "STAMP" -> "штамп";
            case "SHAPE" -> "выделена область";
            default -> "пометка";
        };
    }

    /** Начало выделенного текста: несколько слов, длинное обрывается многоточием. */
    static String shortQuote(String text) {
        if (text == null || text.isBlank()) return null;
        String[] words = text.strip().split("\\s+");
        String head = String.join(" ", Arrays.copyOf(words, Math.min(words.length, QUOTE_WORDS)));
        boolean truncated = words.length > QUOTE_WORDS;
        if (head.length() > QUOTE_CHARS) {
            head = head.substring(0, QUOTE_CHARS).strip();
            truncated = true;
        }
        return truncated ? head + "…" : head;
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max).strip() + "…";
    }

    /** Экранирование для parse_mode=HTML: Telegram требует заменить &, < и >. */
    public static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
