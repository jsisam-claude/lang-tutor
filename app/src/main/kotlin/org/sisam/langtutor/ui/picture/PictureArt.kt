package org.sisam.langtutor.ui.picture

/**
 * Art for the picture vocabulary room, keyed on the vocab WORD — the shape
 * docs/picture-vocabulary.md asks for, so richer art can replace an entry
 * without touching the room. Emoji is the zero-license first cut (same
 * doctrine as the Canvas rewards and synthesized chimes: nothing shipped
 * that needs an art licence audited); the CURATED set is assigned to the
 * other models' queue and lands here as data.
 *
 * A word with no entry simply does not appear in the room — a card with a
 * missing picture teaches nothing and looks broken.
 */
object PictureArt {

    private val EMOJI = mapOf(
        "apple" to "🍎",
        "bag" to "🎒",
        "ball" to "⚽",
        "banana" to "🍌",
        "bear" to "🐻",
        "bird" to "🐦",
        "blue" to "🔵",
        "book" to "📖",
        "bread" to "🍞",
        "brother" to "👦",
        "cat" to "🐱",
        "dance" to "💃",
        "dog" to "🐶",
        "eyes" to "👀",
        "feet" to "🦶",
        "fish" to "🐟",
        "four" to "4️⃣",
        "friend" to "🧑‍🤝‍🧑",
        "grandma" to "👵",
        "grandpa" to "👴",
        "hands" to "🙌",
        "happy" to "😊",
        "hat" to "🎩",
        "head" to "🙂",
        "hungry" to "😋",
        "jump" to "🤸",
        "milk" to "🥛",
        "mom" to "👩",
        "dad" to "👨",
        "mouth" to "👄",
        "nose" to "👃",
        "one" to "1️⃣",
        "pants" to "👖",
        "pencil" to "✏️",
        "red" to "🔴",
        "run" to "🏃",
        "sad" to "😢",
        "scared" to "😨",
        "shirt" to "👕",
        "shoes" to "👟",
        "sing" to "🎤",
        "sister" to "👧",
        "socks" to "🧦",
        "swim" to "🏊",
        "teacher" to "🧑‍🏫",
        "three" to "3️⃣",
        "tired" to "😴",
        "two" to "2️⃣",
    )

    fun emojiFor(word: String): String? = EMOJI[word.lowercase()]
}
