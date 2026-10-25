package com.sapsr.backend.pipeline;

import com.sapsr.backend.academic.AcademicCalendar;
import com.sapsr.backend.repository.SubmissionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Сторож проверки (решение 5; queue-v2.md, раздел 6): раз в 30 секунд отправляет преподавателю работы, чья
 * содержательная проверка не уложилась в тайм-аут, и закрывает FAILED работы, по которым обработчик так и не
 * прислал фазу format. Раньше такие работы оставались в PROCESSING навсегда.
 */
@Component
public class PipelineWatchdog {

    private static final Logger log = LoggerFactory.getLogger(PipelineWatchdog.class);

    private final SubmissionRepository submissions;
    private final PipelineState state;
    private final NoticeSender sender;
    private final PipelineTimeouts timeouts;
    private final Clock clock;

    @Autowired
    public PipelineWatchdog(SubmissionRepository submissions, PipelineState state, NoticeSender sender,
                            PipelineTimeouts timeouts) {
        this(submissions, state, sender, timeouts, Clock.system(AcademicCalendar.ZONE));
    }

    public PipelineWatchdog(SubmissionRepository submissions, PipelineState state, NoticeSender sender,
                            PipelineTimeouts timeouts, Clock clock) {
        this.submissions = submissions;
        this.state = state;
        this.sender = sender;
        this.timeouts = timeouts;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void tick() {
        LocalDateTime now = LocalDateTime.now(clock);
        for (Integer id : submissions.findContentOverdue(now.minus(timeouts.content()))) {
            log.info("Работа #{}: содержательная проверка не уложилась в {} — работа уходит преподавателю",
                    id, timeouts.content());
            handle(id, () -> sender.send(state.onContentTimeout(id)));
        }
        for (Integer id : submissions.findStuck(now.minus(timeouts.stuck()))) {
            handle(id, () -> sender.send(state.onStuck(id)));
        }
    }

    /** Сбой на одной работе не должен оставить остальные без обработки. */
    private static void handle(Integer id, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("Работа #{}: сторож не смог обработать работу", id, e);
        }
    }
}
