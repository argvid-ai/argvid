package ai.argvid.gen0.domain.moment

enum class QualityTier(val wireName: String) {
    Proxy("proxy"),
    Hybrid("hybrid"),
    Hires("hires"),
}

sealed interface MomentState {
    val qualityTier: QualityTier?

    data class CandidateInMemory(
        override val qualityTier: QualityTier,
    ) : MomentState

    data class Encoding(
        override val qualityTier: QualityTier,
    ) : MomentState

    data class Saving(
        override val qualityTier: QualityTier,
    ) : MomentState

    data class Saved(
        override val qualityTier: QualityTier,
    ) : MomentState

    data object SaveFailed : MomentState {
        override val qualityTier: QualityTier? = null
    }

    data object CatalogFailed : MomentState {
        override val qualityTier: QualityTier? = null
    }

    data object AssetMissing : MomentState {
        override val qualityTier: QualityTier? = null
    }

    data object Deleted : MomentState {
        override val qualityTier: QualityTier? = null
    }
}
