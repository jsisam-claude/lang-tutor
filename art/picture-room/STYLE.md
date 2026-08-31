# Picture-room art brief — flat SVG icons, one shared style

You are drawing a SUBSET of the 48 vocabulary pictures for a children's
language-tutor app (the "picture room": a card shows the picture, the parrot
names it in English). The full set must look like ONE artist drew it, so
follow this spec exactly.

## Output

One file per word: `/tmp/claude-0/-home-user-lang-tutor/35a8118d-9780-501b-ad22-a7d04ea5ce94/scratchpad/art/svg/<word>.svg`

## Style spec (shared by all agents — do not deviate)

- Canvas: `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128">`.
- Flat, bold, kid-friendly. Big simple shapes, generous rounding. The
  subject fills most of the canvas (keep ~8px margin), centered.
- Background: NONE (transparent). No frame, no drop shadow.
- Allowed elements ONLY: `<path>`, `<circle>`, `<rect>` (rx allowed),
  `<ellipse>`, `<g>` (plain grouping, no transforms other than none).
  NO gradients, filters, masks, clip-paths, text, strokes-with-dasharray,
  `<use>`, or transforms. Absolute coordinates only.
- Fills: solid hex colors from THIS palette only:
  - green #4CAF50, dark green #2E7D32
  - teal #26A69A, sky blue #42A5F5, dark blue #1565C0
  - coral #FF7043, red #E53935, pink #EC407A
  - yellow #FFCA28, amber #FFA000
  - brown #8D6E63, dark brown #5D4037
  - cream #FFF8E1, white #FFFFFF
  - purple #AB47BC
  - outline/details #33322E
  - skin tones (people only): #F2C9A0, #C98E5A (vary between people)
- Outlines: draw as stroke="#33322E" stroke-width="4" stroke-linejoin="round"
  stroke-linecap="round" on the main shapes (stroke IS allowed; dasharray is
  not). Small interior details may be fill-only.
- 3-6 colors per icon. Simple is better than clever: a child must name the
  object in half a second.
- People (mom, dad, grandma, grandpa, brother, sister, friend, teacher):
  head-and-shoulders bust, round head, simple hair shape, dot eyes, small
  smile; differentiate by hair color/shape and accessories (grandma/grandpa:
  gray hair, glasses; teacher: glasses + collar; friend: TWO busts side by
  side). Neutral, warm, no gender stereypes beyond hair length.
- Emotions (happy, sad, scared, tired, hungry): a large round yellow face
  (#FFCA28) with the expression; tired adds closed eyes + a small "z z";
  hungry adds an open mouth and a small apple near the mouth.
- Numbers (one, two, three, four): the digit does NOT appear — draw that
  many objects (one=1 red ball, two=2 yellow stars, three=3 green apples,
  four=4 blue dots in a 2x2), evenly spaced.
- Colors (red, blue): a big filled circle of that color with the dark
  outline, slightly glossy via ONE small white ellipse highlight.
- Actions (jump, run, swim, dance, sing): a simple stick-figure-plus body
  (round head, rounded limbs as thick outlined paths) mid-action; swim adds
  two wavy water paths; sing adds a microphone and two note-like dots.
- Body parts (eyes, feet, hands, head, mouth, nose): the part large and
  centered, skin tone #F2C9A0 with #33322E outline; eyes = two eyes with
  irises; head = plain face outline with minimal features.

## Quality bar

Before finishing each file, mentally trace every path: closed where it
should be, no coordinates outside 0-128, no self-intersecting scribble.
Validity will be machine-checked and every icon will be LOOKED AT; a messy
icon is rejected and redrawn, so spend your effort on clean geometry.

Your final message: the list of words you completed, one line each, plus any
icon you are unsure reads clearly at small size.
