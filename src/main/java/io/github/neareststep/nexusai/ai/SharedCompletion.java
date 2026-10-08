package io.github.neareststep.nexusai.ai;

import io.github.neareststep.nexusai.api.GenerationError;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * One in-flight reply shared by a placeholder and {@code generate}.
 * The shared future completes normally so an API joiner can read {@link #apiError()}.
 * A missing local API key is the exception: that future completes exceptionally and the slot is dropped.
 */
@ApiStatus.Internal
public final class SharedCompletion {

    private final ModelAnswer answer;
    private final Throwable failure;
    private final GenerationError apiError;
    private final List<String> notes;

    private SharedCompletion(ModelAnswer answer, Throwable failure, GenerationError apiError, List<String> notes) {
        this.answer = answer;
        this.failure = failure;
        this.apiError = apiError;
        this.notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public static SharedCompletion ok(ModelAnswer answer) {
        return new SharedCompletion(answer == null ? ModelAnswer.text("") : answer, null, null, List.of());
    }

    /**
     * @param failure transport or admission failure seen by a placeholder joiner
     * @param apiError API-shaped failure, when the leader already mapped one (quota, cancel, gate)
     */
    public static SharedCompletion fail(Throwable failure, GenerationError apiError) {
        return fail(failure, apiError, List.of());
    }

    /**
     * @param notes validation lines for a JSON joiner. Empty for every other failure.
     */
    public static SharedCompletion fail(Throwable failure, GenerationError apiError, List<String> notes) {
        Throwable error = failure;
        if (error == null) {
            String message = apiError == null || apiError.message() == null || apiError.message().isBlank()
                    ? "Provider error"
                    : apiError.message();
            error = new AiRequestException(AiErrorKind.OTHER, 0, message, null);
        }
        return new SharedCompletion(null, error, apiError, notes);
    }

    public boolean ok() {
        return failure == null && answer != null;
    }

    public ModelAnswer answer() {
        return answer;
    }

    public Throwable failure() {
        return failure;
    }

    public GenerationError apiError() {
        return apiError;
    }

    /** Validation lines stored for a failed JSON call. Empty otherwise. */
    public List<String> notes() {
        return notes;
    }
}
