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
                String studentText = "SUCCESS".equals(status)
                        ? "✅ Ваша работа прошла автоматическую проверку оформления!\nРабота передана преподавателю на содержательную проверку."
                        : "❌ Работа не прошла проверку оформления. Исправьте ошибки и загрузите работу заново.";
                bot.notifyUser(chatId, studentText);
            }

            if ("SUCCESS".equals(status) && submission.getTeacher() != null) {
                String studentName = submission.getStudent() != null
                        ? submission.getStudent().getFullName() : "Студент";
                bot.notifyUser(submission.getTeacher().getTelegramId(),
                        "📄 " + studentName + " прислал(а) работу на проверку.\nОткройте кабинет для просмотра и вынесения вердикта.");
            }

        } catch (Exception e) {
            System.err.println("[RESULT LISTENER] Ошибка обработки: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
