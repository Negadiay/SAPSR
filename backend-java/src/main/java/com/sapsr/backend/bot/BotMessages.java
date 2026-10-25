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
 *
 * Сообщения короткие: заголовок, имя файла, две-три строки итога. Подробности — в приложении,
 * кнопка под сообщением открывает нужный экран ({@link AppLink}).
 */
public final class BotMessages {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm");
    /** Сколько главных ошибок оформления показать прямо в сообщении. */
    private static final int TOP_FORMAT_ERRORS = 3;
    /** Сколько комментариев из файла преподавателя показать в сообщении; остальные — в файле. */
    private static final int MAX_COMMENTS = 15;
    private static final int MAX_COMMENT_CHARS = 300;
    private static final int QUOTE_WORDS = 6;
    private static final int QUOTE_CHARS = 60;
    /** Запас до предела Telegram в 4096 символов. */
    private static final int MESSAGE_BUDGET = 3800;
    private static final String CONTENT_PENDING = "Содержание проверяется — итог появится в этом сообщении.";

    private BotMessages() {}

    // ── приветствие и справка ──────────────────────────────────────────────

    /** role — роль из базы: STUDENT, TEACHER, ADMIN; null — ещё не зарегистрирован. */
    public static String welcome(String role) {
        String title = "<b>SAPSR — проверка курсовых работ</b>\n\n";
        return title + switch (role == null ? "" : role) {
            case "STUDENT" -> "Сдайте работу в приложении: оформление и содержание проверятся автоматически, "
                    + "затем она уйдёт преподавателю. Итог проверки и решение преподавателя придут сюда.";
            case "TEACHER" -> "Работы студентов приходят к вам уже проверенными автоматически. "
                    + "О новых работах сообщу сюда.";
            case "ADMIN" -> "Сроки, курсы групп и данные студентов — в разделе «Управление».";
            default -> "Откройте приложение и выберите роль: студент или преподаватель.";
        };
    }

    public static String help(String role) {
        return switch (role == null ? "" : role) {
            case "STUDENT" -> """
                    <b>Как сдать работу</b>
                    1. Нажмите «Сдать работу».
                    2. Выберите преподавателя и вид работы, прикрепите PDF.
                    3. Через несколько минут здесь появится итог проверки.
                    4. Решение преподавателя и его комментарии тоже придут сюда.""";
            case "TEACHER" -> """
                    <b>Как проверять</b>
                    1. Нажмите «Работы на проверку».
                    2. Откройте работу: итог автоматической проверки, замечания, файл.
                    3. Примите её или верните на доработку с файлом с пометками.""";
            case "ADMIN" -> """
                    <b>Администратору</b>
                    Сроки опроцентовок, курсы групп и данные студентов — в разделе «Управление».""";
            default -> """
                    <b>SAPSR</b>
                    Откройте приложение и выберите роль: студент или преподаватель.""";
        };
    }

    public static String unknownMessage() {
        return "Я присылаю уведомления о работах, а сами работы — в приложении. Справка — /help.";
    }

    public static String startupNotice() {
        return "SAPSR снова работает.";
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

    /** Оформление проверено, содержание ещё проверяется. Это сообщение бот потом заменит итогом. */
    public static String checkingForStudent(String fileName, boolean passed, String errorsJson) {
        Findings f = findings(errorsJson);
        StringBuilder text = header(passed, f, fileName);
        if (passed) {
            if (f.formatWarnings() > 0) text.append("Предупреждений: ").append(f.formatWarnings()).append(".\n");
        } else {
            appendTopErrors(text, f);
        }
        text.append(CONTENT_PENDING);
        if (!passed) text.append("\nРабота уже у преподавателя, срок засчитан. Исправьте ошибки и загрузите новую версию.");
        return text.toString();
    }

    /**
     * Итог проверки. {@code contentChecked} — содержание проверялось; {@code contentNote} — почему оно не проверено
     * (не хватило времени, сбой), null — проверено или не проверялось вовсе.
     */
    public static String resultForStudent(String fileName, boolean passed, String errorsJson,
                                          boolean contentChecked, String contentNote) {
        Findings f = findings(errorsJson);
        StringBuilder text = header(passed, f, fileName);
        if (passed) {
            text.append("Оформление: без ошибок");
            if (f.formatWarnings() > 0) text.append(", предупреждений — ").append(f.formatWarnings());
            text.append(".\n");
        } else {
            appendTopErrors(text, f);
        }
        if (contentNote != null) {
            text.append("Содержание: не проверено (").append(escape(contentNote)).append(").\n");
        } else if (f.content() > 0) {
            text.append("Содержание: ").append(count(f.content(), "замечание", "замечания", "замечаний"))
                    .append(", сдаче не мешают.\n");
        } else if (contentChecked) {
            text.append("Содержание: без замечаний.\n");
        }
        // Решение 9: отклонённая работа тоже у преподавателя — срок сдачи засчитан.
        text.append(passed
                ? "Работа у преподавателя."
                : "Работа у преподавателя, срок засчитан. Исправьте ошибки и загрузите новую версию — она заменит эту.");
        return text.toString();
    }

    private static StringBuilder header(boolean passed, Findings f, String fileName) {
        StringBuilder text = new StringBuilder(passed
                ? "✅ <b>Оформление без ошибок</b>\n"
                : "❌ <b>Ошибки в оформлении: " + Math.max(f.formatErrors(), 1) + "</b>\n");
        return text.append(escape(fileName)).append("\n\n");
    }

    private static void appendTopErrors(StringBuilder text, Findings f) {
        for (String message : f.topErrors()) text.append("• ").append(escape(message)).append('\n');
        int hidden = f.formatErrors() - f.topErrors().size();
        if (hidden > 0) text.append("• и ещё ").append(hidden).append(" — в приложении\n");
        if (!f.topErrors().isEmpty()) text.append('\n');
    }

    /** Проверка не состоялась: файл не читается, зашифрован, проверка зависла. */
    public static String failedForStudent(String fileName, String reason) {
        return "⚠️ <b>Работу не удалось проверить</b>\n"
                + escape(fileName) + "\n\n"
                + escape(reason) + "\n"
                + "Загрузите её снова — преподавателю она не отправлена.";
    }

    /**
     * Работа на проверку преподавателю. {@code firstSubmittedAt} — когда пришла первая версия (у новой версии),
     * {@code contentNote} — почему содержание не проверено; null — отчёт полный.
     */
    public static String resultForTeacher(String studentName, String group, String fileName, boolean passed,
                                          boolean newVersion, LocalDateTime firstSubmittedAt, String errorsJson,
                                          String contentNote) {
        Findings f = findings(errorsJson);
        StringBuilder text = new StringBuilder(newVersion ? "📥 <b>Новая версия работы</b>\n" : "📥 <b>Новая работа</b>\n");
        text.append(escape(studentName));
        if (group != null && !group.isBlank()) text.append(" · ").append(escape(group));
        text.append('\n').append(escape(fileName)).append('\n');
        if (newVersion && firstSubmittedAt != null) {
            text.append("Первая версия — ").append(DATE_TIME.format(firstSubmittedAt)).append('\n');
        }
        text.append('\n').append(passed ? "Оформление: без ошибок" : "Оформление: ошибок — " + f.formatErrors());
        if (contentNote != null) {
            text.append("\nСодержание: не проверено (").append(escape(contentNote)).append(')');
        } else if (f.content() > 0) {
            text.append("\nСодержание: ").append(count(f.content(), "замечание", "замечания", "замечаний"));
            if (f.needsReview() > 0) text.append(", ").append(f.needsReview()).append(" — на ваше решение");
        }
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
                : "🔄 <b>Работа возвращена на доработку</b>\n");
        text.append(escape(fileName)).append('\n');
        text.append("Преподаватель: ").append(escape(teacherName)).append('\n');
        if (comment != null && !comment.isBlank()) {
            text.append('\n').append(escape(cut(comment.strip(), MAX_COMMENT_CHARS))).append('\n');
        }
        if (annotations != null && !annotations.isEmpty()) {
            text.append("\n<b>Комментарии:</b>\n");
            appendComments(text, annotations);
            text.append("\nФайл с пометками — в приложении.");
        } else if (hasReviewFile) {
            text.append("\nПометки — в файле, он в приложении.");
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
                text.append("…и ещё ").append(roots.size() - number).append(" — в файле.\n");
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

    /** «2 замечания», «5 замечаний», «21 замечание». */
    static String count(int n, String one, String few, String many) {
        int mod100 = n % 100, mod10 = n % 10;
        String word = mod100 >= 11 && mod100 <= 14 ? many : mod10 == 1 ? one : mod10 >= 2 && mod10 <= 4 ? few : many;
        return n + " " + word;
    }

    /** Экранирование для parse_mode=HTML: Telegram требует заменить &, < и >. */
    public static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
