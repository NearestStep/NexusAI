package io.github.neareststep.nexusai.generate;

import io.github.neareststep.nexusai.ai.CallTrace;
import io.github.neareststep.nexusai.api.GenerationRequest;
import io.github.neareststep.nexusai.api.GenerationResult;
import io.github.neareststep.nexusai.api.NexusErrorKind;

/**
 * Seams for quotas (a later change) and pre-generate cancellation.
 * Cache hits do not call these methods. {@link #after} is not called for a cache hit.
 * A rate-limit slot taken before {@link #quotaBlock} or {@link #beforeGenerate} is not returned.
 */
public class GenerationHooks {

    /** A null kind allows the call. */
    public NexusErrorKind quotaBlock(CallTrace trace) {
        return null;
    }

    /** True cancels the call with {@link NexusErrorKind#CANCELLED}. */
    public boolean beforeGenerate(CallTrace trace) {
        return false;
    }

    /** Called for the leader, for joiners, and for failures. Not called for a cache hit. */
    public void after(CallTrace trace, GenerationResult result) {
    }

    /** True forces one player-region read even when the prompt text does not need it. */
    public boolean needsPlayerForQuotas(GenerationRequest request) {
        return false;
    }
}
