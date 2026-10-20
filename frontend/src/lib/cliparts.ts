/**
 * Cliparts are small ready-made motifs the customer lays on the design. Like the image frames they
 * are built in code: every clipart is an SVG on a square 100 x 100 canvas, so the editor preview
 * (an `<img>` of the SVG) and the composed print image (the same SVG drawn onto the canvas) show
 * exactly the same motif at any size.
 */

export const CLIPART_CATEGORIES = [
  {
    id: 'love',
    cliparts: [
      'heart',
      'hearts',
      'heartArrow',
      'kiss',
      'diamond',
      'heartLine',
      'infinityHeart',
      'heartbeat',
      'loveLetter',
      'rings',
    ],
  },
  {
    id: 'party',
    cliparts: [
      'balloons',
      'cake',
      'gift',
      'partyHat',
      'confetti',
      'crown',
      'fireworks',
      'trophy',
      'cheers',
      'beer',
      'partyPopper',
      'discoBall',
      'laurelWreath',
    ],
  },
  {
    id: 'nature',
    cliparts: [
      'sun',
      'moon',
      'cloud',
      'rainbow',
      'flower',
      'tulip',
      'leaf',
      'pineTree',
      'palmTree',
      'mountain',
      'oliveBranch',
      'mountainBadge',
      'wave',
      'retroSunset',
      'forest',
    ],
  },
  {
    id: 'animals',
    cliparts: [
      'paw',
      'cat',
      'dog',
      'butterfly',
      'fish',
      'bee',
      'catSilhouette',
      'fox',
      'whaleTail',
      'owl',
      'pawTrail',
    ],
  },
  {
    id: 'symbols',
    cliparts: [
      'star',
      'sparkles',
      'lightning',
      'smiley',
      'speechBubble',
      'peace',
      'musicNote',
      'anchor',
      'coffee',
      'soccerBall',
      'compass',
      'arrows',
      'yinYang',
      'gamepad',
      'camera',
    ],
  },
  {
    id: 'seasons',
    cliparts: [
      'snowflake',
      'christmasTree',
      'pumpkin',
      'easterEgg',
      'ghost',
      'bat',
      'bunny',
      'gingerbread',
      'candle',
    ],
  },
] as const

export type ClipartCategoryId = (typeof CLIPART_CATEGORIES)[number]['id']

export type ClipartId = (typeof CLIPART_CATEGORIES)[number]['cliparts'][number]

export const CLIPART_IDS: readonly ClipartId[] = CLIPART_CATEGORIES.flatMap(
  (category) => category.cliparts,
)

/** Edge length of the clipart's square SVG; also its intrinsic size when drawn onto a canvas. */
const CLIPART_RENDER_SIZE = 1000

const C = {
  red: '#e63946',
  darkRed: '#b5172a',
  pink: '#ff8fab',
  magenta: '#f15bb5',
  orange: '#f4a261',
  darkOrange: '#e76f51',
  yellow: '#ffd166',
  gold: '#e9a100',
  green: '#52b788',
  darkGreen: '#2d6a4f',
  teal: '#2a9d8f',
  sky: '#8ecae6',
  blue: '#118ab2',
  navy: '#1d3557',
  slate: '#577590',
  purple: '#9b5de5',
  brown: '#8d5524',
  darkBrown: '#5c3a1e',
  cream: '#f2d0a9',
  ink: '#2b2d42',
  white: '#ffffff',
  black: '#1a1a1a',
}

/** Rounds coordinates so the SVG stays short. */
function n(value: number): string {
  return String(Math.round(value * 10) / 10)
}

/** A regular star (or, with `inner === outer`, a regular polygon) with its first point up. */
function starPoints(cx: number, cy: number, outer: number, inner: number, points = 5): string {
  const coordinates: string[] = []
  for (let index = 0; index < points * 2; index++) {
    const radius = index % 2 === 0 ? outer : inner
    const angle = (Math.PI * index) / points - Math.PI / 2
    coordinates.push(`${n(cx + radius * Math.cos(angle))},${n(cy + radius * Math.sin(angle))}`)
  }
  return coordinates.join(' ')
}

function star(cx: number, cy: number, outer: number, fill: string, extra = ''): string {
  return `<polygon points="${starPoints(cx, cy, outer, outer * 0.45)}" fill="${fill}" stroke-linejoin="round" ${extra}/>`
}

/** A four-pointed sparkle with concave sides. */
function sparkle(cx: number, cy: number, r: number, fill: string): string {
  return (
    `<path d="M${n(cx)} ${n(cy - r)}Q${n(cx)} ${n(cy)} ${n(cx + r)} ${n(cy)}Q${n(cx)} ${n(cy)} ${n(cx)} ${n(cy + r)}` +
    `Q${n(cx)} ${n(cy)} ${n(cx - r)} ${n(cy)}Q${n(cx)} ${n(cy)} ${n(cx)} ${n(cy - r)}Z" fill="${fill}"/>`
  )
}

/** Repeats `shape` around the centre of the canvas, `count` times evenly. */
function radial(count: number, shape: string, cx = 50, cy = 50): string {
  return Array.from(
    { length: count },
    (_, index) => `<g transform="rotate(${n((360 * index) / count)} ${cx} ${cy})">${shape}</g>`,
  ).join('')
}

/** A heart filling the whole 100 x 100 box; place it with a transform. */
const HEART_PATH =
  'M50 90C20 68 5 52 5 32C5 17 16 7 30 7C39 7 46 12 50 20C54 12 61 7 70 7C84 7 95 17 95 32C95 52 80 68 50 90Z'

function heart(transform: string, fill: string): string {
  return (
    `<g transform="${transform}"><path d="${HEART_PATH}" fill="${fill}"/>` +
    `<ellipse cx="28" cy="28" rx="10" ry="6" transform="rotate(-35 28 28)" fill="${C.white}" opacity="0.35"/></g>`
  )
}

function balloon(cx: number, cy: number, fill: string): string {
  return (
    `<path d="M${cx} ${cy + 19}C${cx - 4} ${cy + 34} ${cx + 6} ${cy + 46} 50 92" stroke="${C.ink}" stroke-width="1.5" fill="none"/>` +
    `<ellipse cx="${cx}" cy="${cy}" rx="14" ry="18" fill="${fill}"/>` +
    `<polygon points="${cx},${cy + 17} ${cx - 3},${cy + 22} ${cx + 3},${cy + 22}" fill="${fill}"/>` +
    `<ellipse cx="${cx - 5}" cy="${cy - 7}" rx="3.5" ry="6" transform="rotate(25 ${cx - 5} ${cy - 7})" fill="${C.white}" opacity="0.45"/>`
  )
}

/** A frosting edge with drips, `width` wide, starting at `x`/`y`. */
function frosting(x: number, y: number, width: number, drips: number, fill: string): string {
  const step = width / drips
  let d = `M${n(x)} ${n(y - 6)}H${n(x + width)}V${n(y)}`
  for (let index = drips; index > 0; index--) {
    const end = x + step * (index - 1)
    d += `Q${n(end + step / 2)} ${n(y + 9)} ${n(end)} ${n(y)}`
  }
  return `<path d="${d}Z" fill="${fill}"/>`
}

/** A black line drawing stroke, the base of the monochrome cliparts. */
function ink(d: string, width = 5): string {
  return `<path d="${d}" stroke="${C.black}" stroke-width="${width}" fill="none" stroke-linecap="round" stroke-linejoin="round"/>`
}

/** Fills `shapes` in black and cuts `holes` (drawn in black) out of them, so the design shows through. */
function cutout(shapes: string, holes: string): string {
  return (
    `<mask id="cut"><rect width="100" height="100" fill="${C.white}"/><g fill="#000" stroke="#000">${holes}</g></mask>` +
    `<g mask="url(#cut)" fill="${C.black}">${shapes}</g>`
  )
}

function ellipse(cx: number, cy: number, rx: number, ry: number, angle: number): string {
  return `<ellipse cx="${n(cx)}" cy="${n(cy)}" rx="${rx}" ry="${ry}" transform="rotate(${n(angle)} ${n(cx)} ${n(cy)})"/>`
}

/** Pairs of leaves along a circular arc around `cx`/`cy`, angled forward like a laurel branch. */
function leafArc(
  cx: number,
  cy: number,
  radius: number,
  from: number,
  to: number,
  count: number,
): string {
  return Array.from({ length: count }, (_, index) => {
    const degrees = from + ((to - from) * index) / (count - 1)
    const angle = (degrees * Math.PI) / 180
    const x = cx + radius * Math.cos(angle)
    const y = cy + radius * Math.sin(angle)
    return [-40, 40]
      .map((tilt) => {
        const leaf = ((degrees + tilt) * Math.PI) / 180
        return ellipse(x - 8 * Math.sin(leaf), y + 8 * Math.cos(leaf), 3.8, 8.5, degrees + tilt)
      })
      .join('')
  }).join('')
}

/** A pine tree silhouette standing on `base`, `height` tall. */
function pine(cx: number, base: number, height: number): string {
  const w = height * 0.62
  const y = (f: number) => n(base - height * f)
  const x = (f: number) => n(cx + w * f)
  return (
    `<polygon points="${x(0)},${y(1)} ${x(0.26)},${y(0.64)} ${x(0.12)},${y(0.64)} ${x(0.38)},${y(0.3)} ${x(0.22)},${y(0.3)} ` +
    `${x(0.5)},${y(0.08)} ${x(-0.5)},${y(0.08)} ${x(-0.22)},${y(0.3)} ${x(-0.38)},${y(0.3)} ${x(-0.12)},${y(0.64)} ${x(-0.26)},${y(0.64)}"/>` +
    `<rect x="${x(-0.07)}" y="${y(0.1)}" width="${n(w * 0.14)}" height="${n(height * 0.1)}"/>`
  )
}

/** A paw print in the 100 x 100 box; place it with a transform. */
const PAW_SHAPES =
  `<path d="M50 50C64 50 76 64 76 76C76 88 62 88 50 84C38 88 24 88 24 76C24 64 36 50 50 50Z"/>` +
  `<ellipse cx="20" cy="46" rx="9" ry="12" transform="rotate(-25 20 46)"/>` +
  `<ellipse cx="38" cy="24" rx="9" ry="13" transform="rotate(-8 38 24)"/>` +
  `<ellipse cx="62" cy="24" rx="9" ry="13" transform="rotate(8 62 24)"/>` +
  `<ellipse cx="80" cy="46" rx="9" ry="12" transform="rotate(25 80 46)"/>`

/** A horizontal arrow with fletching across the canvas, for the crossed arrows. */
const ARROW =
  `<path d="M14 50H84" stroke="${C.black}" stroke-width="4" stroke-linecap="round"/>` +
  `<polygon points="96,50 80,41 84,50 80,59" fill="${C.black}"/>` +
  `<path d="M14 50L6 41M22 50L14 41M30 50L22 41M14 50L6 59M22 50L14 59M30 50L22 59" stroke="${C.black}" stroke-width="3.5" stroke-linecap="round"/>`

const CLIPART_SHAPES: Record<ClipartId, () => string> = {
  heart: () => heart('translate(2 4) scale(0.96)', C.red),

  hearts: () =>
    heart('translate(4 22) scale(0.7)', C.pink) +
    heart('translate(44 4) scale(0.54) rotate(12 50 50)', C.red),

  heartArrow: () =>
    `<line x1="8" y1="84" x2="88" y2="22" stroke="${C.darkBrown}" stroke-width="4" stroke-linecap="round"/>` +
    heart('translate(12 10) scale(0.78)', C.red) +
    `<line x1="52" y1="50" x2="88" y2="22" stroke="${C.darkBrown}" stroke-width="4" stroke-linecap="round"/>` +
    `<polygon points="95,16 88.6,28.6 81.4,19.2" fill="${C.darkBrown}"/>` +
    `<path d="M8 84l-2-10M8 84l10 2M14 79l-2-10M14 79l10 2" stroke="${C.red}" stroke-width="3" stroke-linecap="round"/>`,

  kiss: () =>
    `<path d="M8 50C22 38 32 26 42 30C46 31 48 34 50 37C52 34 54 31 58 30C68 26 78 38 92 50C70 53 30 53 8 50Z" fill="${C.darkRed}"/>` +
    `<path d="M8 50C30 55 70 55 92 50C82 68 66 78 50 78C34 78 18 68 8 50Z" fill="${C.red}"/>` +
    `<path d="M8 50C30 56 70 56 92 50" stroke="${C.darkRed}" stroke-width="2" fill="none"/>` +
    `<ellipse cx="36" cy="64" rx="8" ry="3" fill="${C.white}" opacity="0.35"/>`,

  diamond: () =>
    `<polygon points="28,18 72,18 92,40 8,40" fill="${C.sky}"/>` +
    `<polygon points="8,40 92,40 50,90" fill="${C.blue}"/>` +
    `<polygon points="36,40 64,40 50,90" fill="${C.sky}" opacity="0.6"/>` +
    `<path d="M28 18L36 40L50 18L64 40L72 18M8 40H92" stroke="${C.white}" stroke-width="2" fill="none" stroke-linejoin="round" opacity="0.8"/>`,

  balloons: () => balloon(28, 34, C.red) + balloon(72, 34, C.blue) + balloon(50, 24, C.yellow),

  cake: () =>
    `<ellipse cx="50" cy="88" rx="42" ry="6" fill="#d9d9d9"/>` +
    `<rect x="14" y="58" width="72" height="30" rx="5" fill="${C.pink}"/>` +
    frosting(14, 62, 72, 6, C.white) +
    `<rect x="26" y="38" width="48" height="22" rx="4" fill="${C.magenta}"/>` +
    frosting(26, 42, 48, 4, C.white) +
    [36, 50, 64]
      .map(
        (x) =>
          `<rect x="${x - 2.5}" y="20" width="5" height="16" rx="1" fill="${C.sky}"/>` +
          `<path d="M${x} 8Q${x + 5} 15 ${x} 18Q${x - 5} 15 ${x} 8Z" fill="${C.yellow}"/>`,
      )
      .join(''),

  gift: () =>
    `<rect x="14" y="44" width="72" height="46" rx="3" fill="${C.red}"/>` +
    `<rect x="9" y="32" width="82" height="16" rx="3" fill="#ef476f"/>` +
    `<rect x="44" y="32" width="12" height="58" fill="${C.yellow}"/>` +
    `<ellipse cx="38" cy="22" rx="13" ry="7" transform="rotate(25 38 22)" fill="none" stroke="${C.yellow}" stroke-width="6"/>` +
    `<ellipse cx="62" cy="22" rx="13" ry="7" transform="rotate(-25 62 22)" fill="none" stroke="${C.yellow}" stroke-width="6"/>` +
    `<circle cx="50" cy="30" r="6" fill="${C.gold}"/>`,

  partyHat: () =>
    `<polygon points="50,12 80,84 20,84" fill="${C.purple}"/>` +
    `<circle cx="46" cy="40" r="4" fill="${C.yellow}"/><circle cx="58" cy="56" r="4" fill="${C.magenta}"/>` +
    `<circle cx="40" cy="66" r="4" fill="${C.sky}"/><circle cx="62" cy="74" r="3.5" fill="${C.yellow}"/>` +
    `<path d="M18 84Q50 96 82 84L78 76Q50 86 22 76Z" fill="${C.yellow}"/>` +
    `<circle cx="50" cy="11" r="8" fill="${C.magenta}"/>`,

  confetti: () =>
    [
      [18, 20, 30, C.red],
      [70, 14, -20, C.blue],
      [46, 36, 60, C.yellow],
      [84, 46, 15, C.magenta],
      [24, 60, -45, C.green],
      [58, 70, 25, C.purple],
      [36, 86, 70, C.orange],
      [80, 82, -30, C.teal],
    ]
      .map(
        ([x, y, angle, fill]) =>
          `<rect x="${Number(x) - 6}" y="${Number(y) - 3}" width="12" height="6" rx="1.5" transform="rotate(${angle} ${x} ${y})" fill="${fill}"/>`,
      )
      .join('') +
    `<circle cx="40" cy="14" r="4" fill="${C.teal}"/><circle cx="90" cy="24" r="3.5" fill="${C.yellow}"/>` +
    `<circle cx="10" cy="40" r="3.5" fill="${C.purple}"/><circle cx="66" cy="44" r="4" fill="${C.red}"/>` +
    `<circle cx="14" cy="84" r="4" fill="${C.blue}"/><circle cx="88" cy="64" r="3.5" fill="${C.orange}"/>` +
    `<path d="M36 52q4-6 8 0t8 0" stroke="${C.magenta}" stroke-width="3" fill="none" stroke-linecap="round"/>` +
    `<path d="M54 92q4-6 8 0t8 0" stroke="${C.blue}" stroke-width="3" fill="none" stroke-linecap="round"/>`,

  crown: () =>
    `<path d="M12 74L16 28L34 50L50 16L66 50L84 28L88 74Z" fill="${C.yellow}" stroke="${C.gold}" stroke-width="3" stroke-linejoin="round"/>` +
    `<rect x="10" y="72" width="80" height="14" rx="3" fill="${C.orange}"/>` +
    `<circle cx="16" cy="26" r="5" fill="${C.red}"/><circle cx="50" cy="14" r="5" fill="${C.blue}"/><circle cx="84" cy="26" r="5" fill="${C.red}"/>` +
    `<circle cx="30" cy="79" r="3.5" fill="${C.red}"/><circle cx="50" cy="79" r="3.5" fill="${C.blue}"/><circle cx="70" cy="79" r="3.5" fill="${C.red}"/>`,

  fireworks: () => {
    const burst = (cx: number, cy: number, inner: number, outer: number, color: string) =>
      radial(
        12,
        `<line x1="${cx}" y1="${cy - inner}" x2="${cx}" y2="${cy - outer}" stroke="${color}" stroke-width="3.5" stroke-linecap="round"/>` +
          `<circle cx="${cx}" cy="${cy - outer - 5}" r="2" fill="${color}"/>`,
        cx,
        cy,
      )
    return (
      burst(38, 40, 8, 26, C.magenta) + burst(74, 70, 5, 16, C.yellow) + sparkle(78, 18, 8, C.sky)
    )
  },

  trophy: () =>
    `<path d="M30 20H16C16 36 22 44 32 46M70 20H84C84 36 78 44 68 46" stroke="${C.gold}" stroke-width="6" fill="none" stroke-linecap="round"/>` +
    `<path d="M26 10H74V34C74 52 64 62 50 62C36 62 26 52 26 34Z" fill="${C.yellow}"/>` +
    `<rect x="44" y="60" width="12" height="14" fill="${C.gold}"/>` +
    `<rect x="32" y="72" width="36" height="8" rx="2" fill="${C.orange}"/>` +
    `<rect x="24" y="80" width="52" height="12" rx="2" fill="${C.brown}"/>` +
    star(50, 34, 12, C.white, 'opacity="0.8"'),

  sun: () =>
    radial(12, `<polygon points="45,22 55,22 50,4" fill="${C.orange}"/>`) +
    `<circle cx="50" cy="50" r="26" fill="${C.yellow}"/>` +
    `<circle cx="42" cy="42" r="8" fill="${C.white}" opacity="0.3"/>`,

  moon: () =>
    `<path d="M58 8A42 42 0 1 0 92 70A34 34 0 1 1 58 8Z" fill="${C.yellow}"/>` +
    star(74, 26, 9, C.yellow) +
    star(88, 46, 5, C.yellow) +
    sparkle(60, 50, 6, C.sky),

  cloud: () =>
    `<g fill="${C.sky}"><circle cx="32" cy="58" r="18"/><circle cx="54" cy="44" r="24"/><circle cx="74" cy="60" r="16"/>` +
    `<rect x="16" y="58" width="74" height="18" rx="9"/></g>` +
    `<circle cx="46" cy="38" r="8" fill="${C.white}" opacity="0.45"/>`,

  rainbow: () =>
    [C.red, C.orange, C.yellow, C.green, C.blue, C.purple]
      .map(
        (color, index) =>
          `<path d="M${8 + index * 7} 78A${42 - index * 7} ${42 - index * 7} 0 0 1 ${92 - index * 7} 78" stroke="${color}" stroke-width="7.5" fill="none"/>`,
      )
      .join(''),

  flower: () =>
    `<g fill="${C.pink}">` +
    radial(6, `<ellipse cx="50" cy="26" rx="13" ry="22"/>`) +
    `</g>` +
    `<circle cx="50" cy="50" r="14" fill="${C.yellow}"/>` +
    `<circle cx="45" cy="45" r="4" fill="${C.white}" opacity="0.5"/>`,

  tulip: () =>
    `<path d="M50 54V94" stroke="${C.darkGreen}" stroke-width="5" stroke-linecap="round"/>` +
    `<path d="M50 90C38 88 26 76 24 60C38 64 48 74 50 90Z" fill="${C.green}"/>` +
    `<path d="M50 84C62 82 74 70 76 56C62 60 52 70 50 84Z" fill="${C.green}"/>` +
    `<path d="M28 14L40 28L50 10L60 28L72 14C76 40 66 58 50 58C34 58 24 40 28 14Z" fill="${C.red}"/>` +
    `<path d="M40 28C40 40 44 50 50 58C56 50 60 40 60 28L50 10Z" fill="${C.darkRed}" opacity="0.35"/>`,

  leaf: () =>
    `<path d="M14 86C14 44 46 14 88 12C88 54 58 86 14 86Z" fill="${C.green}"/>` +
    `<path d="M14 86L72 28M36 64L34 46M36 64L54 66M52 48L52 32M52 48L68 48" stroke="${C.darkGreen}" stroke-width="3" stroke-linecap="round" fill="none"/>`,

  pineTree: () =>
    `<rect x="44" y="78" width="12" height="16" fill="${C.brown}"/>` +
    `<polygon points="50,6 74,38 26,38" fill="${C.darkGreen}"/>` +
    `<polygon points="50,22 80,58 20,58" fill="${C.darkGreen}"/>` +
    `<polygon points="50,40 88,80 12,80" fill="${C.darkGreen}"/>` +
    `<path d="M50 6L74 38H62ZM50 22L80 58H66ZM50 40L88 80H72Z" fill="${C.green}" opacity="0.6"/>`,

  palmTree: () =>
    `<ellipse cx="50" cy="92" rx="34" ry="6" fill="#f4d58d"/>` +
    `<path d="M46 92C48 70 52 50 58 32" stroke="${C.brown}" stroke-width="8" stroke-linecap="round" fill="none"/>` +
    `<g fill="${C.green}">` +
    `<path d="M58 30C42 16 24 20 10 34C26 28 42 28 58 30Z"/>` +
    `<path d="M58 30C72 14 88 16 96 30C82 26 70 28 58 30Z"/>` +
    `<path d="M58 30C48 34 34 46 30 64C42 50 50 40 58 30Z"/>` +
    `<path d="M58 30C68 36 80 46 84 62C72 50 64 40 58 30Z"/>` +
    `<path d="M58 30C52 18 54 8 64 4C62 14 60 22 58 30Z"/>` +
    `</g>` +
    `<circle cx="55" cy="35" r="4" fill="${C.darkBrown}"/><circle cx="62" cy="35" r="4" fill="${C.darkBrown}"/>`,

  mountain: () =>
    `<circle cx="76" cy="24" r="11" fill="${C.yellow}"/>` +
    `<polygon points="54,86 72,48 96,86" fill="${C.sky}"/>` +
    `<polygon points="4,86 38,26 72,86" fill="${C.slate}"/>` +
    `<polygon points="38,26 48,44 42,41 37,47 32,41 28,44" fill="${C.white}"/>`,

  paw: () =>
    `<g fill="${C.brown}">` +
    `<path d="M50 50C64 50 76 64 76 76C76 88 62 88 50 84C38 88 24 88 24 76C24 64 36 50 50 50Z"/>` +
    `<ellipse cx="20" cy="46" rx="9" ry="12" transform="rotate(-25 20 46)"/>` +
    `<ellipse cx="38" cy="24" rx="9" ry="13" transform="rotate(-8 38 24)"/>` +
    `<ellipse cx="62" cy="24" rx="9" ry="13" transform="rotate(8 62 24)"/>` +
    `<ellipse cx="80" cy="46" rx="9" ry="12" transform="rotate(25 80 46)"/>` +
    `</g>`,

  cat: () =>
    `<polygon points="16,46 20,10 46,30" fill="${C.orange}"/><polygon points="84,46 80,10 54,30" fill="${C.orange}"/>` +
    `<polygon points="23,36 24,20 37,30" fill="${C.pink}"/><polygon points="77,36 76,20 63,30" fill="${C.pink}"/>` +
    `<ellipse cx="50" cy="58" rx="36" ry="30" fill="${C.orange}"/>` +
    `<path d="M40 30Q50 38 60 30" stroke="${C.darkOrange}" stroke-width="3" fill="none" stroke-linecap="round"/>` +
    `<ellipse cx="37" cy="54" rx="4.5" ry="7" fill="${C.ink}"/><ellipse cx="63" cy="54" rx="4.5" ry="7" fill="${C.ink}"/>` +
    `<circle cx="38.5" cy="51" r="1.5" fill="${C.white}"/><circle cx="64.5" cy="51" r="1.5" fill="${C.white}"/>` +
    `<polygon points="45,65 55,65 50,71" fill="${C.pink}"/>` +
    `<path d="M50 71Q46 77 40 74M50 71Q54 77 60 74M8 60L28 64M8 72L28 68M92 60L72 64M92 72L72 68" stroke="${C.ink}" stroke-width="2" fill="none" stroke-linecap="round"/>`,

  dog: () =>
    `<ellipse cx="50" cy="54" rx="30" ry="32" fill="#c68b59"/>` +
    `<ellipse cx="20" cy="46" rx="11" ry="24" transform="rotate(18 20 46)" fill="${C.darkBrown}"/>` +
    `<ellipse cx="80" cy="46" rx="11" ry="24" transform="rotate(-18 80 46)" fill="${C.darkBrown}"/>` +
    `<ellipse cx="50" cy="70" rx="16" ry="12" fill="${C.cream}"/>` +
    `<circle cx="39" cy="48" r="4.5" fill="${C.ink}"/><circle cx="61" cy="48" r="4.5" fill="${C.ink}"/>` +
    `<circle cx="40.5" cy="46.5" r="1.5" fill="${C.white}"/><circle cx="62.5" cy="46.5" r="1.5" fill="${C.white}"/>` +
    `<ellipse cx="50" cy="63" rx="7" ry="5" fill="${C.ink}"/>` +
    `<path d="M50 68V73M42 74Q50 80 58 74" stroke="${C.ink}" stroke-width="2.5" fill="none" stroke-linecap="round"/>` +
    `<path d="M46 77Q50 88 54 77Z" fill="${C.red}"/>`,

  butterfly: () =>
    `<ellipse cx="30" cy="34" rx="22" ry="17" transform="rotate(-30 30 34)" fill="${C.purple}"/>` +
    `<ellipse cx="70" cy="34" rx="22" ry="17" transform="rotate(30 70 34)" fill="${C.purple}"/>` +
    `<ellipse cx="34" cy="66" rx="15" ry="13" transform="rotate(30 34 66)" fill="${C.magenta}"/>` +
    `<ellipse cx="66" cy="66" rx="15" ry="13" transform="rotate(-30 66 66)" fill="${C.magenta}"/>` +
    `<circle cx="28" cy="32" r="5" fill="${C.white}" opacity="0.7"/><circle cx="72" cy="32" r="5" fill="${C.white}" opacity="0.7"/>` +
    `<circle cx="34" cy="68" r="3.5" fill="${C.yellow}"/><circle cx="66" cy="68" r="3.5" fill="${C.yellow}"/>` +
    `<ellipse cx="50" cy="52" rx="4.5" ry="26" fill="${C.ink}"/>` +
    `<path d="M48 28Q42 14 34 10M52 28Q58 14 66 10" stroke="${C.ink}" stroke-width="2.5" fill="none" stroke-linecap="round"/>`,

  fish: () =>
    `<polygon points="70,50 94,30 90,50 94,70" fill="${C.darkOrange}"/>` +
    `<path d="M40 32Q50 18 62 34Z" fill="${C.darkOrange}"/>` +
    `<ellipse cx="44" cy="52" rx="32" ry="21" fill="${C.orange}"/>` +
    `<path d="M50 34Q58 52 50 70M60 37Q67 52 60 67" stroke="${C.darkOrange}" stroke-width="2.5" fill="none"/>` +
    `<circle cx="27" cy="47" r="5" fill="${C.ink}"/><circle cx="28.5" cy="45.5" r="1.8" fill="${C.white}"/>` +
    `<path d="M14 58Q20 62 24 58" stroke="${C.ink}" stroke-width="2" fill="none" stroke-linecap="round"/>` +
    `<circle cx="14" cy="26" r="4" stroke="${C.sky}" stroke-width="2" fill="none"/><circle cx="22" cy="14" r="2.5" stroke="${C.sky}" stroke-width="2" fill="none"/>`,

  bee: () =>
    `<polygon points="74,58 86,58 74,64" fill="${C.ink}"/>` +
    `<ellipse cx="52" cy="60" rx="26" ry="20" fill="${C.yellow}"/>` +
    `<path d="M46 40.5V79.5M58 40.8V79.2M70 46V74" stroke="${C.ink}" stroke-width="6"/>` +
    `<circle cx="27" cy="56" r="11" fill="${C.ink}"/>` +
    `<circle cx="24" cy="53" r="2.5" fill="${C.white}"/>` +
    `<path d="M24 46Q20 34 12 32M30 46Q32 34 40 30" stroke="${C.ink}" stroke-width="2" fill="none" stroke-linecap="round"/>` +
    `<ellipse cx="46" cy="32" rx="10" ry="16" transform="rotate(-25 46 32)" fill="#e0f4fb" stroke="${C.sky}" stroke-width="2.5" opacity="0.95"/>` +
    `<ellipse cx="62" cy="32" rx="10" ry="16" transform="rotate(25 62 32)" fill="#e0f4fb" stroke="${C.sky}" stroke-width="2.5" opacity="0.95"/>`,

  star: () => star(50, 53, 46, C.yellow, `stroke="${C.gold}" stroke-width="4"`),

  sparkles: () =>
    sparkle(40, 56, 34, C.yellow) + sparkle(78, 22, 16, C.sky) + sparkle(80, 76, 10, C.magenta),

  lightning: () =>
    `<polygon points="62,4 20,54 46,54 34,96 80,40 54,40 68,4" fill="${C.yellow}" stroke="${C.gold}" stroke-width="3" stroke-linejoin="round"/>`,

  smiley: () =>
    `<circle cx="50" cy="50" r="42" fill="${C.yellow}" stroke="${C.gold}" stroke-width="3"/>` +
    `<ellipse cx="36" cy="40" rx="5" ry="8" fill="${C.ink}"/><ellipse cx="64" cy="40" rx="5" ry="8" fill="${C.ink}"/>` +
    `<path d="M28 58Q50 82 72 58" stroke="${C.ink}" stroke-width="5" fill="none" stroke-linecap="round"/>` +
    `<circle cx="24" cy="58" r="6" fill="${C.pink}" opacity="0.7"/><circle cx="76" cy="58" r="6" fill="${C.pink}" opacity="0.7"/>`,

  speechBubble: () =>
    `<path d="M14 12H86A8 8 0 0 1 94 20V62A8 8 0 0 1 86 70H46L24 90L30 70H14A8 8 0 0 1 6 62V20A8 8 0 0 1 14 12Z" fill="${C.white}" stroke="${C.ink}" stroke-width="4" stroke-linejoin="round"/>`,

  peace: () =>
    `<circle cx="50" cy="50" r="38" stroke="${C.teal}" stroke-width="9" fill="none"/>` +
    `<path d="M50 12V88M50 50L23 77M50 50L77 77" stroke="${C.teal}" stroke-width="9" fill="none"/>`,

  musicNote: () =>
    `<g fill="${C.navy}">` +
    `<ellipse cx="28" cy="76" rx="13" ry="9" transform="rotate(-20 28 76)"/>` +
    `<ellipse cx="72" cy="66" rx="13" ry="9" transform="rotate(-20 72 66)"/>` +
    `<rect x="36" y="22" width="5" height="54"/><rect x="80" y="12" width="5" height="54"/>` +
    `<polygon points="36,22 85,10 85,24 36,36"/>` +
    `</g>`,

  anchor: () =>
    `<g stroke="${C.navy}" stroke-width="7" fill="none" stroke-linecap="round">` +
    `<circle cx="50" cy="15" r="8"/><path d="M50 23V88M32 36H68M16 62C18 80 34 88 50 88C66 88 82 80 84 62"/>` +
    `</g>` +
    `<polygon points="6,66 26,66 16,52" fill="${C.navy}"/><polygon points="74,66 94,66 84,52" fill="${C.navy}"/>`,

  coffee: () =>
    `<path d="M38 26C32 20 44 16 38 8M52 26C46 20 58 16 52 8" stroke="#adb5bd" stroke-width="3.5" fill="none" stroke-linecap="round"/>` +
    `<ellipse cx="46" cy="90" rx="38" ry="5" fill="#d9d9d9"/>` +
    `<path d="M72 44C90 42 90 70 70 70" stroke="${C.darkOrange}" stroke-width="7" fill="none"/>` +
    `<path d="M18 34H74V60C74 76 62 88 46 88C30 88 18 76 18 60Z" fill="${C.darkOrange}"/>` +
    `<ellipse cx="46" cy="34" rx="28" ry="5" fill="${C.darkBrown}"/>` +
    heart('translate(36 52) scale(0.2)', C.white),

  soccerBall: () =>
    `<clipPath id="ball"><circle cx="50" cy="50" r="41"/></clipPath>` +
    `<circle cx="50" cy="50" r="41" fill="${C.white}"/>` +
    `<g clip-path="url(#ball)">` +
    `<polygon points="${starPoints(50, 50, 13, 13 * Math.cos(Math.PI / 5), 5)}" fill="${C.ink}"/>` +
    radial(
      5,
      `<line x1="50" y1="37" x2="50" y2="22" stroke="${C.ink}" stroke-width="2.5"/>` +
        `<polygon points="${starPoints(50, 9, 13, 13 * Math.cos(Math.PI / 5), 5)}" fill="${C.ink}" transform="rotate(180 50 9)"/>`,
    ) +
    `</g>` +
    `<circle cx="50" cy="50" r="41" stroke="${C.ink}" stroke-width="4" fill="none"/>`,

  snowflake: () =>
    radial(
      6,
      `<path d="M50 50V8M50 22L40 13M50 22L60 13M50 36L42 29M50 36L58 29" stroke="${C.blue}" stroke-width="5" fill="none" stroke-linecap="round"/>`,
    ) + `<circle cx="50" cy="50" r="6" fill="${C.blue}"/>`,

  christmasTree: () =>
    `<rect x="44" y="80" width="12" height="14" fill="${C.brown}"/>` +
    `<polygon points="50,14 72,40 28,40" fill="${C.darkGreen}"/>` +
    `<polygon points="50,28 78,60 22,60" fill="${C.darkGreen}"/>` +
    `<polygon points="50,44 88,82 12,82" fill="${C.darkGreen}"/>` +
    `<path d="M34 40Q50 48 66 38M28 60Q50 70 74 56M20 80Q50 90 82 76" stroke="${C.yellow}" stroke-width="2.5" fill="none"/>` +
    `<circle cx="42" cy="50" r="4" fill="${C.red}"/><circle cx="60" cy="68" r="4" fill="${C.sky}"/>` +
    `<circle cx="36" cy="72" r="4" fill="${C.yellow}"/><circle cx="66" cy="48" r="3.5" fill="${C.magenta}"/>` +
    star(50, 13, 11, C.yellow, `stroke="${C.gold}" stroke-width="2"`),

  pumpkin: () =>
    `<path d="M46 30C46 20 50 12 58 10L60 15C55 17 53 23 54 30Z" fill="#6a994e"/>` +
    `<ellipse cx="32" cy="60" rx="20" ry="30" fill="${C.darkOrange}"/>` +
    `<ellipse cx="68" cy="60" rx="20" ry="30" fill="${C.darkOrange}"/>` +
    `<ellipse cx="50" cy="60" rx="20" ry="31" fill="${C.orange}"/>` +
    `<polygon points="30,56 44,56 37,44" fill="${C.darkBrown}"/><polygon points="56,56 70,56 63,44" fill="${C.darkBrown}"/>` +
    `<path d="M28 66Q50 84 72 66Q64 70 60 68L56 74L50 70L44 74L40 68Q36 70 28 66Z" fill="${C.darkBrown}"/>`,

  easterEgg: () =>
    `<path d="M50 6C72 6 86 40 86 60C86 80 70 94 50 94C30 94 14 80 14 60C14 40 28 6 50 6Z" fill="${C.sky}"/>` +
    `<path d="M24 32H76" stroke="${C.purple}" stroke-width="5"/>` +
    `<path d="M16 54L26 46L36 54L46 46L56 54L66 46L76 54L84 47" stroke="${C.magenta}" stroke-width="5" fill="none" stroke-linejoin="round"/>` +
    `<circle cx="32" cy="72" r="4.5" fill="${C.yellow}"/><circle cx="50" cy="74" r="4.5" fill="${C.yellow}"/><circle cx="68" cy="72" r="4.5" fill="${C.yellow}"/>` +
    `<ellipse cx="36" cy="22" rx="5" ry="9" transform="rotate(25 36 22)" fill="${C.white}" opacity="0.4"/>`,
  heartLine: () =>
    `<g transform="translate(4 16) scale(0.78)"><path d="${HEART_PATH}" stroke="${C.black}" stroke-width="7.5" fill="none" stroke-linejoin="round"/></g>` +
    `<g fill="${C.black}"><path d="${HEART_PATH}" transform="translate(70 4) scale(0.22)"/><path d="${HEART_PATH}" transform="translate(86 28) scale(0.12)"/></g>`,

  infinityHeart: () =>
    ink('M50 56C38 38 8 36 8 56C8 76 38 74 50 56C62 38 92 36 92 56C92 76 62 74 50 56Z', 6) +
    `<path d="${HEART_PATH}" transform="translate(40 14) scale(0.2)" fill="${C.black}"/>`,

  heartbeat: () =>
    `<g transform="translate(14 12) scale(0.72)"><path d="${HEART_PATH}" stroke="${C.black}" stroke-width="7" fill="none" stroke-linejoin="round"/></g>` +
    ink('M4 50H30L36 38L44 64L52 28L58 50H96', 5),

  loveLetter: () =>
    `<rect x="10" y="26" width="80" height="54" rx="4" stroke="${C.black}" stroke-width="5" fill="none"/>` +
    ink('M12 30L50 58L88 30M12 78L38 56M88 78L62 56', 4.5) +
    `<path d="${HEART_PATH}" transform="translate(40 49) scale(0.2)" fill="${C.black}"/>`,

  rings: () =>
    `<g stroke="${C.black}" stroke-width="6" fill="none"><circle cx="37" cy="60" r="23"/><circle cx="63" cy="60" r="23"/></g>` +
    `<polygon points="63,37 54,28 58,21 68,21 72,28" fill="${C.black}"/>` +
    sparkle(84, 18, 7, C.black),

  cheers: () => {
    const flute =
      `<path d="M-9 -40H9L7 -12C6 -4 -6 -4 -7 -12Z"/>` +
      `<rect x="-1.75" y="-8" width="3.5" height="32"/><ellipse cx="0" cy="25" rx="10" ry="3"/>`
    return (
      `<g fill="${C.black}"><g transform="translate(33 64) rotate(18)">${flute}</g><g transform="translate(67 64) rotate(-18)">${flute}</g></g>` +
      sparkle(50, 11, 7, C.black) +
      ink('M34 12L39 18M66 12L61 18', 3)
    )
  },

  beer: () =>
    `<rect x="20" y="34" width="46" height="56" rx="5" stroke="${C.black}" stroke-width="6" fill="none"/>` +
    ink('M66 46H76C82 46 84 50 84 56V68C84 74 82 78 76 78H66', 6) +
    ink('M33 48V80M43 48V80M53 48V80', 4) +
    `<path d="M16 38C12 28 20 20 28 22C30 12 44 10 48 18C54 10 68 14 68 24C76 24 76 38 70 38Z" fill="${C.black}"/>`,

  partyPopper: () =>
    `<polygon points="8,94 30,46 56,72" fill="${C.black}"/>` +
    ink('M46 36L54 26M62 52L74 48M58 40L72 28', 4) +
    ink('M40 14q4 4 0 8t0 8M80 58q4 4 0 8t0 8', 3) +
    `<g fill="${C.black}"><circle cx="54" cy="14" r="3.5"/><circle cx="90" cy="38" r="3.5"/><circle cx="72" cy="80" r="3"/><circle cx="28" cy="30" r="3"/></g>` +
    sparkle(80, 16, 9, C.black),

  discoBall: () =>
    `<clipPath id="ball"><circle cx="50" cy="58" r="30"/></clipPath>` +
    ink('M50 6V28', 3) +
    `<g clip-path="url(#ball)" stroke="${C.black}" stroke-width="2.5" fill="none">` +
    `<path d="M10 40H90M10 50H90M10 60H90M10 70H90M10 80H90"/>` +
    `<ellipse cx="50" cy="58" rx="10" ry="30"/><ellipse cx="50" cy="58" rx="21" ry="30"/></g>` +
    `<g clip-path="url(#ball)" fill="${C.black}"><rect x="40" y="40" width="10" height="10"/><rect x="29" y="60" width="11" height="10"/><rect x="50" y="70" width="10" height="10"/><rect x="60" y="50" width="11" height="10"/></g>` +
    `<circle cx="50" cy="58" r="30" stroke="${C.black}" stroke-width="5" fill="none"/>` +
    sparkle(16, 24, 8, C.black) +
    sparkle(86, 28, 6, C.black),

  laurelWreath: () => {
    const branch =
      ink('M56 88A36 36 0 0 1 32 21', 3.5) +
      `<g fill="${C.black}">${leafArc(50, 52, 36, 100, 236, 6)}</g>`
    return branch + `<g transform="translate(100 0) scale(-1 1)">${branch}</g>`
  },

  oliveBranch: () =>
    ink('M10 90C30 70 56 42 88 12', 4) +
    `<g fill="${C.black}">` +
    [0.18, 0.34, 0.5, 0.66, 0.82]
      .map((t, index) => {
        const x = 10 + 78 * t
        const y = 90 - 78 * t
        return (
          ellipse(x - 8, y - 6, 4.5, 11, -10) +
          (index < 4 ? ellipse(x + 7, y + 6, 4.5, 11, 80) : '')
        )
      })
      .join('') +
    ellipse(90, 10, 4.5, 10, 45) +
    `<circle cx="44" cy="70" r="4.5"/><circle cx="66" cy="46" r="4.5"/></g>`,

  mountainBadge: () =>
    `<clipPath id="badge"><circle cx="50" cy="50" r="38"/></clipPath>` +
    `<g clip-path="url(#badge)" fill="${C.black}"><polygon points="2,92 34,44 48,62 66,32 98,92"/><circle cx="30" cy="30" r="7"/></g>` +
    ink('M58 22q3-3 6 0q3-3 6 0M70 30q2-2 4 0q2-2 4 0', 2.5) +
    `<circle cx="50" cy="50" r="44" stroke="${C.black}" stroke-width="5" fill="none"/>`,

  wave: () =>
    ink('M8 78C8 46 32 22 58 22C78 22 90 36 86 52C82 64 66 64 62 54C58 44 68 38 74 42', 6) +
    ink('M20 78C22 58 36 42 54 40M32 78C34 66 42 58 52 56', 4) +
    ink('M6 88q8-6 16 0t16 0t16 0t16 0t16 0t16 0', 4.5),

  retroSunset: () =>
    cutout(
      `<path d="M12 72A38 38 0 0 1 88 72Z"/>`,
      `<rect x="0" y="44" width="100" height="3"/><rect x="0" y="54" width="100" height="4"/><rect x="0" y="64" width="100" height="5"/>`,
    ) + ink('M6 80H94M20 90H80', 5),

  forest: () =>
    `<g fill="${C.black}">${pine(28, 86, 50)}${pine(72, 86, 56)}${pine(50, 88, 76)}</g>` +
    ink('M6 90H94', 4),

  catSilhouette: () =>
    `<g fill="${C.black}">` +
    `<path d="M30 92C22 92 20 80 24 66C28 52 36 46 46 46C58 46 64 56 66 70C68 82 66 92 58 92Z"/>` +
    `<circle cx="46" cy="34" r="15"/><polygon points="32,30 33,10 45,22"/><polygon points="47,22 59,10 60,30"/></g>` +
    ink('M62 90C78 92 92 82 88 70C85 62 76 64 78 70', 6),

  fox: () =>
    `<polygon points="50,86 14,46 20,14 38,34 62,34 80,14 86,46" stroke="${C.black}" stroke-width="4.5" fill="none" stroke-linejoin="round"/>` +
    ink('M14 46L50 58L86 46M38 34L50 58L62 34M50 58V80M20 14L28 40M80 14L72 40', 3) +
    `<g fill="${C.black}"><polygon points="44,80 56,80 50,87"/><circle cx="35" cy="47" r="3.5"/><circle cx="65" cy="47" r="3.5"/></g>`,

  whaleTail: () =>
    `<path d="M50 82C49 70 47 58 42 52C32 46 16 46 8 32C20 36 34 36 44 42C46 40 48 36 50 30C52 36 54 40 56 42C66 36 80 36 92 32C84 46 68 46 58 52C53 58 51 70 50 82Z" fill="${C.black}"/>` +
    ink('M8 86q7-5 14 0t14 0t14 0t14 0t14 0t14 0', 4) +
    `<g fill="${C.black}"><circle cx="30" cy="20" r="2.5"/><circle cx="68" cy="16" r="3"/><circle cx="76" cy="24" r="2"/></g>`,

  owl: () =>
    cutout(
      `<path d="M22 34C22 20 34 12 50 12C66 12 78 20 78 34V68C78 82 66 90 50 90C34 90 22 82 22 68Z"/>` +
        `<polygon points="22,36 20,6 40,20"/><polygon points="78,36 80,6 60,20"/>`,
      `<circle cx="38" cy="40" r="12"/><circle cx="62" cy="40" r="12"/><polygon points="45,52 55,52 50,60"/>` +
        `<path d="M36 68q3.5 4 7 0q3.5 4 7 0q3.5 4 7 0q3.5 4 7 0M40 77q3.5 4 7 0q3.5 4 7 0q3.5 4 7 0" stroke-width="2.5" fill="none" stroke-linecap="round"/>`,
    ) +
    `<g fill="${C.black}"><circle cx="38" cy="40" r="6"/><circle cx="62" cy="40" r="6"/></g>` +
    ink('M12 94H88', 4),

  pawTrail: () =>
    `<g fill="${C.black}">` +
    [
      [4, 66, -20],
      [26, 46, 10],
      [48, 30, -20],
      [70, 8, 10],
    ]
      .map(
        ([x, y, angle]) =>
          `<g transform="translate(${x} ${y}) rotate(${angle} 13 13) scale(0.26)">${PAW_SHAPES}</g>`,
      )
      .join('') +
    `</g>`,

  compass: () =>
    `<circle cx="50" cy="50" r="42" stroke="${C.black}" stroke-width="5" fill="none"/>` +
    `<circle cx="50" cy="50" r="33" stroke="${C.black}" stroke-width="2" fill="none"/>` +
    `<polygon points="50,14 58,50 42,50" fill="${C.black}"/>` +
    `<polygon points="42,50 58,50 50,86" stroke="${C.black}" stroke-width="2.5" fill="none" stroke-linejoin="round"/>` +
    ink('M20 50H30M70 50H80', 3) +
    `<circle cx="50" cy="50" r="3.5" fill="${C.black}"/>`,

  arrows: () =>
    `<g transform="rotate(35 50 50)">${ARROW}</g><g transform="rotate(-35 50 50)">${ARROW}</g>`,

  yinYang: () =>
    cutout(
      `<path d="M50 8A42 42 0 0 1 50 92A21 21 0 0 1 50 50A21 21 0 0 0 50 8Z"/>`,
      `<circle cx="50" cy="71" r="6"/>`,
    ) +
    `<circle cx="50" cy="50" r="42" stroke="${C.black}" stroke-width="4" fill="none"/>` +
    `<circle cx="50" cy="29" r="6" fill="${C.black}"/>`,

  gamepad: () =>
    cutout(
      `<path d="M24 30H76C88 30 94 42 96 58C98 72 92 82 84 80C78 79 74 70 68 66H32C26 70 22 79 16 80C8 82 2 72 4 58C6 42 12 30 24 30Z"/>`,
      `<path d="M25 40h6v6h6v6h-6v6h-6v-6h-6v-6h6Z"/><circle cx="70" cy="45" r="4.5"/><circle cx="79" cy="54" r="4.5"/>` +
        `<rect x="42" y="42" width="6" height="3" rx="1.5"/><rect x="52" y="42" width="6" height="3" rx="1.5"/>`,
    ),

  camera: () =>
    cutout(
      `<path d="M10 30H30L36 20H64L70 30H90A6 6 0 0 1 96 36V80A6 6 0 0 1 90 86H10A6 6 0 0 1 4 80V36A6 6 0 0 1 10 30Z"/>`,
      `<circle cx="50" cy="57" r="21"/><rect x="78" y="38" width="10" height="5" rx="2"/>`,
    ) + `<circle cx="50" cy="57" r="13" fill="${C.black}"/>`,

  ghost: () =>
    cutout(
      `<path d="M20 90V44C20 24 34 10 50 10C66 10 80 24 80 44V90L70 82L60 90L50 82L40 90L30 82Z"/>`,
      `<ellipse cx="40" cy="42" rx="5" ry="7"/><ellipse cx="60" cy="42" rx="5" ry="7"/><ellipse cx="50" cy="60" rx="5" ry="7"/>`,
    ),

  bat: () =>
    `<path d="M50 36C53 36 55 32 55 27L59 33C62 35 64 40 63 45C71 36 86 32 96 38C88 42 86 50 88 58C80 52 72 54 68 62C62 56 56 60 50 72C44 60 38 56 32 62C28 54 20 52 12 58C14 50 12 42 4 38C14 32 29 36 37 45C36 40 38 35 41 33L45 27C45 32 47 36 50 36Z" fill="${C.black}"/>`,

  bunny: () =>
    cutout(
      `<ellipse cx="50" cy="68" rx="28" ry="24"/>` +
        ellipse(37, 30, 9, 25, -12) +
        ellipse(63, 30, 9, 25, 12),
      ellipse(37, 30, 3.5, 17, -12) +
        ellipse(63, 30, 3.5, 17, 12) +
        `<circle cx="40" cy="64" r="4"/><circle cx="60" cy="64" r="4"/><polygon points="45,73 55,73 50,79"/>` +
        `<path d="M50 79V83M24 72L38 76M24 82L38 79M76 72L62 76M76 82L62 79" stroke-width="2" fill="none" stroke-linecap="round"/>`,
    ),

  gingerbread: () =>
    cutout(
      `<circle cx="50" cy="22" r="15"/><rect x="12" y="38" width="76" height="14" rx="7"/><rect x="31" y="34" width="38" height="40" rx="12"/>` +
        `<path d="M41 66L31 88M59 66L69 88" stroke="${C.black}" stroke-width="15" stroke-linecap="round"/>`,
      `<circle cx="44.5" cy="19" r="2.8"/><circle cx="55.5" cy="19" r="2.8"/><circle cx="50" cy="47" r="3"/><circle cx="50" cy="59" r="3"/>` +
        `<path d="M44 26q6 5 12 0M18 40q2 4 4 0t4 0M74 40q2 4 4 0t4 0M27 82q3 3 6 0M67 82q3 3 6 0" stroke-width="2.5" fill="none" stroke-linecap="round"/>`,
    ),

  candle: () =>
    `<path d="M50 8C59 19 61 28 50 36C39 28 41 19 50 8Z" fill="${C.black}"/>` +
    ink('M50 36V42M32 16L26 11M68 16L74 11M28 30H21M72 30H79', 3) +
    cutout(
      `<rect x="37" y="42" width="26" height="46" rx="3"/>`,
      `<path d="M57 42V56" stroke-width="3.5" stroke-linecap="round"/>`,
    ) +
    ink('M24 90H76', 5),
}

/** The clipart as a standalone square SVG document. */
export function clipartSvg(clipartId: ClipartId): string {
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" width="${CLIPART_RENDER_SIZE}" height="${CLIPART_RENDER_SIZE}" viewBox="0 0 100 100">` +
    CLIPART_SHAPES[clipartId]() +
    '</svg>'
  )
}

export function clipartUrl(clipartId: ClipartId): string {
  return `data:image/svg+xml,${encodeURIComponent(clipartSvg(clipartId))}`
}

/** Clipart edge length bounds and default, as fractions of the print frame width. */
export const CLIPART_MIN_SIZE = 0.06
export const CLIPART_MAX_SIZE = 1
export const CLIPART_DEFAULT_SIZE = 0.3
