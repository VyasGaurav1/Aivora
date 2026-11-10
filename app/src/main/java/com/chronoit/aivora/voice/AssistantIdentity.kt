package com.chronoit.aivora.voice

import com.chronoit.aivora.ProductIdentity

data class AssistantIdentity(
    val assistantDisplayName: String = ProductIdentity.DEFAULT_ASSISTANT_NAME,
    val wakeName: String = ProductIdentity.DEFAULT_ASSISTANT_NAME,
) {
    init {
        require(assistantDisplayName.isNotBlank() && assistantDisplayName.length <= 40)
        require(wakeName.isNotBlank() && wakeName.length <= 40)
        require(assistantDisplayName.none(Char::isISOControl))
        require(wakeName.none(Char::isISOControl))
    }
}
