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
            int score = json.has("score") ? json.get("score").asInt() : -1;

            Optional<Submission> optSubmission = submissionRepository.findById(taskId);
            if (optSubmission.isEmpty()) {
                System.out.println("[RESULT LISTENER] Submission не найден: task_id=" + taskId);
                return;
            }

            Submission submission = optSubmission.get();

            if ("SUCCESS".equals(status)) {
                submission.setStatus("SUCCESS");
            } else {
                submission.setStatus("REJECTED");
            }
            submission.setFormatErrors(errorsJson);
            if (score >= 0) submission.setScore(score);
            submissionRepository.save(submission);
            System.out.println("[RESULT LISTENER] Submission #" + taskId + " обновлён -> " + submission.getStatus() + " (score=" + score + ")");

            if (submission.getStudent() != null) {
                Long chatId = submission.getStudent().getTelegramId();
                String studentText;
                if ("SUCCESS".equals(status)) {
                    studentText = "✅ Ваша работа прошла автоматическую проверку форматирования!" +
                            (score >= 0 ? "\nОценка оформления: " + score + "/100" : "") +
                            "\nРабота передана преподавателю на смысловую проверку.";
                } else {
                    studentText = "❌ Работа не прошла проверку форматирования." +
                            (score >= 0 ? "\nОценка оформления: " + score + "/100" : "") +
                            "\nИсправьте ошибки и загрузите работу заново.";
                }
                bot.notifyUser(chatId, studentText);
            }

            if ("SUCCESS".equals(status) && submission.getTeacher() != null) {
                Long teacherChatId = submission.getTeacher().getTelegramId();
                String studentName = submission.getStudent() != null
                        ? submission.getStudent().getFullName()
                        : "Студент";
                String teacherText = "📄 " + studentName + " прислал(а) работу на проверку.\n" +
                        "Оценка форматирования: " + score + "/100\n" +
                        "Откройте кабинет для просмотра и вынесения вердикта.";
                bot.notifyUser(teacherChatId, teacherText);
            }

        } catch (Exception e) {
            System.err.println("[RESULT LISTENER] Ошибка обработки: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
