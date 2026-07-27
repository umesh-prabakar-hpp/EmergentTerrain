package com.emergentterrain.research;

import com.emergentterrain.repo.PaperOpenQuestionRepository;
import com.emergentterrain.research.conjecture.Conjecture;
import com.emergentterrain.research.question.PaperOpenQuestion;
import com.emergentterrain.research.question.QuestionServedProtocolCloneGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Explicit, dormant host-database smoke for the question-served protocol clone screen.
 *
 * <p>It persists one temporary question, invokes the real allocation service, and requires the
 * call to return before a draft ID or narration can be requested. The temporary row is removed in
 * all cases. It is deliberately not part of the normal scheduler: enable only with
 * {@code app.research.question-serve.clone-smoke.enabled=true}.
 */
@Component
@ConditionalOnProperty(value = "app.research.question-serve.clone-smoke.enabled", havingValue = "true")
public class QuestionServedProtocolCloneSmokeRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QuestionServedProtocolCloneSmokeRunner.class);
    private static final String SOURCE_PAPER_ID = "SMOKE-QUESTION-SERVED-CLONE";

    private final ResearchCycleService cycle;
    private final PaperOpenQuestionRepository questions;
    private final boolean questionServeEnabled;

    public QuestionServedProtocolCloneSmokeRunner(
            ResearchCycleService cycle,
            PaperOpenQuestionRepository questions,
            @Value("${app.research.question-serve.enabled:false}") boolean questionServeEnabled) {
        this.cycle = cycle;
        this.questions = questions;
        this.questionServeEnabled = questionServeEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!questionServeEnabled) {
            throw new IllegalStateException("question-serve clone smoke requires app.research.question-serve.enabled=true");
        }
        Conjecture conjecture = new Conjecture("math", "abundant_density", "happy_density", 0.0, 0.2515);
        conjecture.setConfirmedRuns(8);
        conjecture.setP99Slack(-0.004);
        PaperOpenQuestion question = questions.saveAndFlush(new PaperOpenQuestion(
                SOURCE_PAPER_ID,
                conjecture.lhs(),
                "Does the originally registered relationship hold on a further range?",
                "SCOPE",
                QuestionServedProtocolCloneGuard.fingerprint(ConjecturePreRegistration.from(conjecture))));
        try {
            cycle.publishConjectureInvestigation(UUID.randomUUID(), researcher(), conjecture, 1L,
                    new CloneOnlyContext());
            PaperOpenQuestion after = questions.findById(question.id()).orElseThrow();
            if (after.answeredByPaperId() != null || after.answeredAt() != null) {
                throw new IllegalStateException("clone smoke reserved its declined question");
            }
            log.info("question-serve clone smoke: PASS sourcePaperId={} family={} "+
                            "(no draft ID, reservation, or narration)",
                    SOURCE_PAPER_ID, conjecture.lhs());
        } finally {
            questions.deleteById(question.id());
        }
    }

    private static Researcher researcher() {
        BotLifecycleState state = new BotLifecycleState();
        state.setGenome(BotGenome.initialise(UUID.randomUUID(), 0));
        return new Researcher(UUID.randomUUID().toString(), state);
    }

    private static final class CloneOnlyContext implements ResearchCycleService.Context {
        @Override
        public String nextPaperId(UUID roomId) {
            throw new IllegalStateException("clone smoke reached draft-ID allocation");
        }

        @Override
        public List<ResearchPaper> getPublishedPapers(UUID roomId, int limit) {
            return List.of();
        }

        @Override
        public void publishPaper(PaperDraft draft, NarrationResult narration) {
            throw new IllegalStateException("clone smoke reached fallback publication");
        }

        @Override
        public Consumer<PaperDraft> narratingCallback() {
            return draft -> {
                throw new IllegalStateException("clone smoke reached narration");
            };
        }

        @Override
        public Function<Object[], Object> formulationCallback() {
            return null;
        }
    }
}
