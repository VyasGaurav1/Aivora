package com.chronoit.aivora.data

import kotlinx.coroutines.sync.Mutex

/** Mode transitions and learning commits are serialized so enabling privacy has a definite boundary. */
object PrivacyGate { val mutex = Mutex() }
