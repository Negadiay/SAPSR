package com.sapsr.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sapsr.backend.SapsrTelegramBot;
import com.sapsr.backend.bot.BotMessages;
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
            boolean passed = "SUCCESS".equals(status);
            submission.setStatus(passed ? "SUCCESS" : "REJECTED");
            submission.setFormatErrors(errorsJson);
            submissionRepository.save(submission);
            System.out.println("[RESULT LISTENER] Submission #" + taskId + " -> " + submission.getStatus());

            // Студент уже загрузил новую версию: результат старой сохраняем, но никого не тревожим.
            if (submissionRepository.existsByParentId(submission.getId())) {
                System.out.println("[RESULT LISTENER] Submission #" + taskId + " заменена новой версией — без уведомлений");
                return;
            }

            if (submission.getStudent() != null) {
                bot.notifyUser(submission.getStudent().getTelegramId(),
                        BotMessages.resultForStudent(submission.displayFileName(), passed, errorsJson));
            }

            // Решение 9: преподаватель получает работу и при ошибках оформления — так засчитывается срок сдачи.
            if (submission.getTeacher() != null) {
                String studentName = submission.getStudent() != null
                        ? submission.getStudent().getFullName() : "Студент";
                Integer msgId = bot.notifyUser(submission.getTeacher().getTelegramId(),
                        BotMessages.resultForTeacher(studentName, submission.displayFileName(), passed,
                                submission.getParentId() != null, submission.getFirstSubmittedAt(), errorsJson));
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
}
