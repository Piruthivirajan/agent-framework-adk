package com.demo.agentfw.eval;

import java.util.List;

/**
 * A saved calibration run, so a later run can be compared with it.
 *
 * @param label    short name, for example {@code baseline} or {@code stripped}
 * @param note     what was different about this run (pipeline settings)
 * @param context  models and funnel settings in force
 * @param outcomes one entry per question
 */
public record CalibrationRun(String label, String note, CalibrationReport.Context context,
                             List<QuestionOutcome> outcomes) {
}
