package com.openardf.serialslinger.model

object CloneTemplateEligibility {
    fun hasCompleteTimedEventSettings(
        settings: DeviceSettings,
        daysRemaining: Int?,
        productName: String?,
    ): Boolean {
        if (settings.idCodeSpeedWpm !in 5..20) {
            return false
        }
        val isArducon = productName.equals("Arducon", ignoreCase = true)
        if (!isArducon) {
            if (
                EventProfileSupport.patternSpeedBelongsToTimedEventSettings(settings.eventType) &&
                settings.patternCodeSpeedWpm !in 5..20
            ) {
                return false
            }
        }
        if (
            !JvmTimeSupport.isCloneScheduleEligible(
                startTimeCompact = settings.startTimeCompact,
                finishTimeCompact = settings.finishTimeCompact,
                currentTimeCompact = settings.currentTimeCompact,
                daysToRun = settings.daysToRun,
                daysRemaining = daysRemaining,
            )
        ) {
            return false
        }
        if (settings.daysToRun !in 1..255) {
            return false
        }
        if (isArducon) {
            return true
        }
        val frequencyVisibility = EventProfileSupport.timedEventFrequencyVisibility(settings.eventType)
        if (frequencyVisibility.showFrequency1 && settings.lowFrequencyHz == null) {
            return false
        }
        if (frequencyVisibility.showFrequency2 && settings.mediumFrequencyHz == null) {
            return false
        }
        if (frequencyVisibility.showFrequency3 && settings.highFrequencyHz == null) {
            return false
        }
        if (frequencyVisibility.showFrequencyB && settings.beaconFrequencyHz == null) {
            return false
        }
        return true
    }
}
