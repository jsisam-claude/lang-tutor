package org.sisam.langtutor.ui.picture

import org.sisam.langtutor.R

/**
 * Art for the picture vocabulary room, keyed on the vocab WORD — the shape
 * docs/picture-vocabulary.md asks for, so richer art can replace an entry
 * without touching the room.
 *
 * Two tiers, best first: [drawableFor] serves the CURATED set — the flat
 * vector icons drawn by the art queue (one shared style spec, reviewed as a
 * set, converted from SVG by the batch converter; license-clean because they
 * were drawn for this app). [emojiFor] remains the zero-license fallback so
 * a word whose icon hasn't landed yet still gets a card, and so deleting a
 * bad icon can never empty the room.
 *
 * A word with no entry in EITHER map simply does not appear in the room — a
 * card with a missing picture teaches nothing and looks broken.
 */
object PictureArt {

    private val DRAWABLES = mapOf(
        "apple" to R.drawable.pic_apple,
        "bag" to R.drawable.pic_bag,
        "ball" to R.drawable.pic_ball,
        "banana" to R.drawable.pic_banana,
        "bear" to R.drawable.pic_bear,
        "bird" to R.drawable.pic_bird,
        "blue" to R.drawable.pic_blue,
        "book" to R.drawable.pic_book,
        "bread" to R.drawable.pic_bread,
        "brother" to R.drawable.pic_brother,
        "cat" to R.drawable.pic_cat,
        "dance" to R.drawable.pic_dance,
        "dog" to R.drawable.pic_dog,
        "eyes" to R.drawable.pic_eyes,
        "feet" to R.drawable.pic_feet,
        "fish" to R.drawable.pic_fish,
        "four" to R.drawable.pic_four,
        "friend" to R.drawable.pic_friend,
        "grandma" to R.drawable.pic_grandma,
        "grandpa" to R.drawable.pic_grandpa,
        "hands" to R.drawable.pic_hands,
        "happy" to R.drawable.pic_happy,
        "hat" to R.drawable.pic_hat,
        "head" to R.drawable.pic_head,
        "hungry" to R.drawable.pic_hungry,
        "jump" to R.drawable.pic_jump,
        "milk" to R.drawable.pic_milk,
        "mom" to R.drawable.pic_mom,
        "dad" to R.drawable.pic_dad,
        "mouth" to R.drawable.pic_mouth,
        "nose" to R.drawable.pic_nose,
        "one" to R.drawable.pic_one,
        "pants" to R.drawable.pic_pants,
        "pencil" to R.drawable.pic_pencil,
        "red" to R.drawable.pic_red,
        "run" to R.drawable.pic_run,
        "sad" to R.drawable.pic_sad,
        "scared" to R.drawable.pic_scared,
        "shirt" to R.drawable.pic_shirt,
        "shoes" to R.drawable.pic_shoes,
        "sing" to R.drawable.pic_sing,
        "sister" to R.drawable.pic_sister,
        "socks" to R.drawable.pic_socks,
        "swim" to R.drawable.pic_swim,
        "teacher" to R.drawable.pic_teacher,
        "three" to R.drawable.pic_three,
        "tired" to R.drawable.pic_tired,
        "two" to R.drawable.pic_two,
    )

    fun drawableFor(word: String): Int? = DRAWABLES[word.lowercase()]

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
