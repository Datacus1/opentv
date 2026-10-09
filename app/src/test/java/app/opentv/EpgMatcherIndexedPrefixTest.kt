/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.repo.EpgMatcher
import app.opentv.data.parser.ChannelNameNormalizer
import org.junit.Assert.*
import org.junit.Test

class EpgMatcherIndexedPrefixTest {
    @Test
    fun `indexed prefix tier agrees with conservative linear matching across shuffled ambiguous keys`() {
        val aliases = (0 until 1000).map { "id$it" to "Station $it" } + listOf(
            "east" to "Common News East", "west" to "Common News West",
            "same" to "Same East", "same" to "Same West",
            "collision1" to "Collision", "collision2" to "Collision",
            "linear" to "AMC", "plus" to "AMC+",
        )
        val normalized = aliases.flatMap { (id, name) ->
            listOf(name, id).map { id to ChannelNameNormalizer.normalize(it).groupKey }
        }.filter { it.second.length >= 2 }
        val exact = normalized.groupBy { it.second }.mapValues { (_, entries) ->
            entries.map { it.first }.distinct().singleOrNull()
        }
        fun linear(key: String): String? {
            if (key.length < 2) return null
            exact[key]?.let { return it }
            return normalized.filter { (_, alias) ->
                key.contains("plus") == alias.contains("plus") &&
                    (key.startsWith(alias) || alias.startsWith(key))
            }.map { it.first }.distinct().singleOrNull()
        }
        val index = EpgMatcher.buildIndex(aliases.shuffled(kotlin.random.Random(17)))
        val queries = normalized.flatMap { (_, key) ->
            listOf(key, key + "extra", key.take((key.length - 1).coerceAtLeast(1)))
        } + listOf("missing", "station", "commonnews", "same", "collision", "amc", "amcplus")
        queries.distinct().forEach { query ->
            assertEquals("Prefix contract for $query", linear(query), index.match(query))
        }
        assertTrue(index.hasAmbiguousPrefix("commonnews"))
        assertFalse(index.hasAmbiguousPrefix("same"))
        assertFalse(index.hasAmbiguousPrefix("missing"))
    }
}
