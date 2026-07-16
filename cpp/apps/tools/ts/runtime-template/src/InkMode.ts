// Director ink/blend modes. Verbatim port of `enum class InkMode` and its helpers in
// cpp/include/libreshockwave/id/Ids.hpp (line 214). Values are the Director ink codes.

export const InkMode = {
  COPY: 0,
  TRANSPARENT: 1,
  REVERSE: 2,
  GHOST: 3,
  NOT_COPY: 4,
  NOT_TRANSPARENT: 5,
  NOT_REVERSE: 6,
  NOT_GHOST: 7,
  MATTE: 8,
  MASK: 9,
  BLEND: 32,
  ADD_PIN: 33,
  ADD: 34,
  SUBTRACT_PIN: 35,
  BACKGROUND_TRANSPARENT: 36,
  LIGHTEST: 37,
  SUBTRACT: 38,
  DARKEST: 39,
  LIGHTEN: 40,
  DARKEN: 41,
} as const;

export type InkMode = (typeof InkMode)[keyof typeof InkMode];

/** Numeric Director ink code for an InkMode value (identity). */
export function code(mode: InkMode): number {
  return mode;
}

/** True for the inks that consult the sprite's blend percentage (0..100). */
export function usesBlend(mode: InkMode): boolean {
  switch (mode) {
    case InkMode.BLEND:
    case InkMode.ADD_PIN:
    case InkMode.ADD:
    case InkMode.SUBTRACT_PIN:
    case InkMode.SUBTRACT:
    case InkMode.LIGHTEST:
    case InkMode.DARKEST:
    case InkMode.LIGHTEN:
    case InkMode.DARKEN:
      return true;
    default:
      return false;
  }
}

/** Resolves a Director ink code to an InkMode, defaulting to COPY for unknown codes. */
export function inkModeFromCode(value: number): InkMode {
  switch (value) {
    case 0:
      return InkMode.COPY;
    case 1:
      return InkMode.TRANSPARENT;
    case 2:
      return InkMode.REVERSE;
    case 3:
      return InkMode.GHOST;
    case 4:
      return InkMode.NOT_COPY;
    case 5:
      return InkMode.NOT_TRANSPARENT;
    case 6:
      return InkMode.NOT_REVERSE;
    case 7:
      return InkMode.NOT_GHOST;
    case 8:
      return InkMode.MATTE;
    case 9:
      return InkMode.MASK;
    case 32:
      return InkMode.BLEND;
    case 33:
      return InkMode.ADD_PIN;
    case 34:
      return InkMode.ADD;
    case 35:
      return InkMode.SUBTRACT_PIN;
    case 36:
      return InkMode.BACKGROUND_TRANSPARENT;
    case 37:
      return InkMode.LIGHTEST;
    case 38:
      return InkMode.SUBTRACT;
    case 39:
      return InkMode.DARKEST;
    case 40:
      return InkMode.LIGHTEN;
    case 41:
      return InkMode.DARKEN;
    default:
      return InkMode.COPY;
  }
}

/**
 * Parses a Director ink name (case/separator-insensitive) to an InkMode, or undefined when
 * the name is not recognized. Mirrors inkModeFromName() in Ids.hpp.
 */
export function inkModeFromName(name: string): InkMode | undefined {
  const normalized = name.replace(/[_\-\s]/g, "").toLowerCase();
  switch (normalized) {
    case "copy":
      return InkMode.COPY;
    case "transparent":
      return InkMode.TRANSPARENT;
    case "reverse":
      return InkMode.REVERSE;
    case "ghost":
      return InkMode.GHOST;
    case "notcopy":
      return InkMode.NOT_COPY;
    case "nottransparent":
      return InkMode.NOT_TRANSPARENT;
    case "notreverse":
      return InkMode.NOT_REVERSE;
    case "notghost":
      return InkMode.NOT_GHOST;
    case "matte":
      return InkMode.MATTE;
    case "mask":
      return InkMode.MASK;
    case "blend":
      return InkMode.BLEND;
    case "addpin":
      return InkMode.ADD_PIN;
    case "add":
      return InkMode.ADD;
    case "subtractpin":
      return InkMode.SUBTRACT_PIN;
    case "backgroundtransparent":
    case "bgtransparent":
      return InkMode.BACKGROUND_TRANSPARENT;
    case "lightest":
      return InkMode.LIGHTEST;
    case "subtract":
      return InkMode.SUBTRACT;
    case "darkest":
      return InkMode.DARKEST;
    case "lighten":
      return InkMode.LIGHTEN;
    case "darken":
      return InkMode.DARKEN;
    default:
      return undefined;
  }
}