package com.orbitspace.kernel;

/** Selected by job type. Expensive processing runs outside the result-acceptance transaction. */
public interface AsyncJobProcessor extends AsyncJobPort.ResultTarget {
    String jobType();

    /** Return the immutable result object key. Publication occurs via AsyncJobPort.acceptResult. */
    String process(AsyncJobPort.Attempt attempt) throws Exception;
}
