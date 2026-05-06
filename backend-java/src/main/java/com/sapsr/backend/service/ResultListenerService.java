package com.sapsr.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapsr.backend.SapsrTelegramBot;
import com.sapsr.backend.entity.Submission;
import com.sapsr.backend.repository.SubmissionRepository;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ResultListenerService {

    private final SubmissionRepository submissionRepository;
    private final SapsrTelegramBot bot;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ResultListenerService(SubmissionRepository submissionRepository, SapsrTelegramBot bot) {
        this.submissionRepository = submissionRepository;
        this.bot = bot;
    }

    @RabbitListener(queues = "${sapsr.rabbitmq.results-queue}")
    public void handleResult(String message) {
        System.out.println("[RESULT LISTENER] Получен результат: " + message);

        try {
            JsonNode json = objectMapper.readTree(message);
            int taskId = json.get("task_id").asInt();
            String status = json.get("status").asText();
            String errorsJson = json.has("errors") ? json.get("errors").toString() : "[]";

            Optional<Submission> optSubmission = submissionRepository.findById(taskId);
            if (optSubmission.isEmpty()) {
                System.out.println("[RESULT LISTENER] Submission не найден: task_id=" + taskId);
                return;
            }

            Submission submission = optSubmission.get();
            submission.setStatus("SUCCESS".equals(status) ? "SUCCESS" : "REJECTED");
            submission.setFormatErrors(errorsJson);
            submissionRepository.save(submission);
            System.out.println("[RESULT LISTENER] Submission #" + taskId + " -> " + submission.getStatus());

            if (submission.getStudent() != null) {
                Long chatId = submission.getStudent().getTelegramId();
                String fileName = submissionFileName(submission);
                int warningCount = countReportWarnings(errorsJson);
                int errorCount = countReportErrors(errorsJson);
                String warningLine = warningCount > 0
                        ? "В отчёте есть предупреждения: " + warningCount + ". Для ознакомления скачайте отчёт в мини-приложении."
                        : "Предупреждений в отчёте проверки нет. Отчёт можно скачать в мини-приложении.";
                String studentText = "SUCCESS".equals(status)
                        ? "✅ Ваша работа прошла автоматическую проверку оформления!\n"
                        + "Работа: " + fileName + "\n"
                        + warningLine + "\n"
                        + "Работа передана преподавателю на содержательную проверку."
                        : "❌ Работа не прошла проверку оформления.\n"
                        + "Работа: " + fileName + "\n"
                        + "Ошибок: " + errorCount + ", предупреждений: " + warningCount + ".\n"
                        + formatReportFindings(errorsJson)
                        + "Исправьте ошибки и загрузите работу заново. Полный отчёт доступен в мини-приложении.";
                bot.notifyUser(chatId, studentText);
            }

            if ("SUCCESS".equals(status) && submission.getTeacher() != null) {
                String studentName = submission.getStudent() != null
                        ? submission.getStudent().getFullName() : "Студент";
                Integer msgId = bot.notifyUser(submission.getTeacher().getTelegramId(),
                        "📄 " + studentName + " прислал(а) работу на проверку.\nОткройте кабинет для просмотра и вынесения вердикта.");
                if (msgId != null) {
                    submission.setTeacherMessageId(msgId);
                    submissionRepository.save(submission);
                }
            }

        } catch (Exception e) {
            System.err.println("[RESULT LISTENER] Ошибка обработки: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private int countReportWarnings(String errorsJson) {
        try {
            JsonNode errors = objectMapper.readTree(errorsJson);
            if (!errors.isArray()) return 0;
            int count = 0;
            for (JsonNode error : errors) {
                String severity = error.has("severity") ? error.get("severity").asText("") : "";
                if (!"critical".equals(severity) && !"major".equals(severity)) count++;
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private int countReportErrors(String errorsJson) {
        try {
            JsonNode errors = objectMapper.readTree(errorsJson);
            if (!errors.isArray()) return 0;
            int count = 0;
            for (JsonNode error : errors) {
                String severity = error.has("severity") ? error.get("severity").asText("") : "";
                if ("critical".equals(severity) || "major".equals(severity)) count++;
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private String formatReportFindings(String errorsJson) {
        try {
            JsonNode errors = objectMapper.readTree(errorsJson);
            if (!errors.isArray() || errors.isEmpty()) return "";

            StringBuilder sb = new StringBuilder("Основные замечания:\n");
            int shown = 0;
            for (JsonNode error : errors) {
                if (shown >= 5) break;
                String severity = error.has("severity") ? error.get("severity").asText("") : "";
                String prefix = ("critical".equals(severity) || "major".equals(severity)) ? "Ошибка" : "Предупреждение";
                String message = error.has("message") ? error.get("message").asText("") : "";
                if (message.isBlank()) continue;
                sb.append("• ").append(prefix).append(": ").append(message).append("\n");
                shown++;
            }
            if (errors.size() > shown) {
                sb.append("И ещё замечаний: ").append(errors.size() - shown).append(".\n");
            }
            return sb.append("\n").toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String submissionFileName(Submission submission) {
        String fp = submission.getFilePath();
        if (fp == null || fp.isBlank()) return "file.pdf";
        String name = fp.contains("/") ? fp.substring(fp.lastIndexOf('/') + 1) : fp;
        name = name.contains("\\") ? name.substring(name.lastIndexOf('\\') + 1) : name;
        if (name.matches("^\\d+_.*")) name = name.substring(name.indexOf('_') + 1);
        return name;
    }
}
