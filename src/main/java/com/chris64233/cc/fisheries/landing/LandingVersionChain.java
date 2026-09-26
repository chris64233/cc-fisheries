package com.chris64233.cc.fisheries.landing;

import java.util.List;

/**
 * 申报版本链：原申报（重量不可覆盖）、复核结论与按时间排列的更正链。
 */
public record LandingVersionChain(LandingRecord landing, LandingReview review,
                                  List<LandingCorrection> corrections) {
}
