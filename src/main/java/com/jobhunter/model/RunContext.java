package com.jobhunter.model;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Describes where the current run's output lives.
 *
 * @param date      today's date (used for the output/<date>/ folder name)
 * @param runNo     1-based run number for today (never reused, never overwritten)
 * @param runFolder the folder output/<date>/<runNo>/ — dashboard.html goes here
 */
public record RunContext(LocalDate date, int runNo, Path runFolder) {
}
