/**
 * CaptchaOCR - Client-side Pure JavaScript OCR Solver
 * Uses template matching on 4-digit numeric captchas from sfd.ui.ac.ir
 * Based on templates.json extracted from Calinan system.
 */

class CaptchaOCR {
  constructor() {
    this.templates = null;
    this.slots = [
      [76, 96],
      [97, 117],
      [117, 138],
      [138, 158]
    ];
  }

  async loadTemplates(url = "./templates.json") {
    if (this.templates) return this.templates;
    try {
      const resp = await fetch(url);
      if (!resp.ok) throw new Error(`HTTP error ${resp.status} loading templates`);
      this.templates = await resp.json();
      return this.templates;
    } catch (e) {
      console.error("Failed to load OCR templates:", e);
      throw e;
    }
  }

  /**
   * Solves a captcha image from an Image element, Blob, or Canvas
   * @param {Blob|HTMLImageElement|ImageBitmap} imageSource
   * @returns {Promise<{code: string, confidence: number}|null>}
   */
  async solve(imageSource) {
    if (!this.templates) {
      await this.loadTemplates();
    }

    // Load into canvas
    let img;
    if (imageSource instanceof Blob) {
      img = await this._blobToImage(imageSource);
    } else if (imageSource instanceof HTMLImageElement) {
      img = imageSource;
    } else if (typeof ImageBitmap !== "undefined" && imageSource instanceof ImageBitmap) {
      img = imageSource;
    } else {
      throw new Error("Unsupported image source for OCR");
    }

    const w = img.width;
    const h = img.height;
    if (w < 160 || h < 35) {
      console.warn(`Captcha dimensions too small: ${w}x${h}`);
      return null;
    }

    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext("2d", { willReadFrequently: true });
    ctx.drawImage(img, 0, 0);

    const imgData = ctx.getImageData(0, 0, w, h);
    const pixels = imgData.data;

    // 1. Grayscale & Threshold (> 190)
    const thresh = new Array(h);
    for (let y = 0; y < h; y++) {
      thresh[y] = new Uint8Array(w);
      const rowOffset = y * w * 4;
      for (let x = 0; x < w; x++) {
        const idx = rowOffset + (x * 4);
        const r = pixels[idx];
        const g = pixels[idx + 1];
        const b = pixels[idx + 2];
        const lum = (0.299 * r + 0.587 * g + 0.114 * b);
        thresh[y][x] = lum > 190 ? 1 : 0;
      }
    }

    // 2. 3x3 Morphological Dilation (MaxFilter 3)
    const closed = new Array(h);
    for (let y = 0; y < h; y++) {
      closed[y] = new Uint8Array(w);
      for (let x = 0; x < w; x++) {
        let maxVal = 0;
        for (let dy = -1; dy <= 1; dy++) {
          const ny = y + dy;
          if (ny >= 0 && ny < h) {
            for (let dx = -1; dx <= 1; dx++) {
              const nx = x + dx;
              if (nx >= 0 && nx < w && thresh[ny][nx] === 1) {
                maxVal = 1;
                break;
              }
            }
          }
          if (maxVal === 1) break;
        }
        closed[y][x] = maxVal;
      }
    }

    // 3. Match each slot
    let predicted = "";
    const scores = [];

    for (const [x1, x2] of this.slots) {
      if (x2 > w) continue;

      // Extract slice for y in 8..32
      const candRaw = [];
      const maxYSlice = Math.min(33, h);
      for (let y = 8; y < maxYSlice; y++) {
        const row = new Uint8Array(x2 - x1);
        for (let x = x1; x < x2; x++) {
          row[x - x1] = closed[y][x];
        }
        candRaw.push(row);
      }

      // Find active bounding box (rows and columns with at least one 1)
      let minY = -1, maxY = -1;
      for (let y = 0; y < candRaw.length; y++) {
        if (candRaw[y].some(v => v === 1)) {
          if (minY === -1) minY = y;
          maxY = y;
        }
      }
      if (minY === -1) return null;

      let minX = -1, maxX = -1;
      const slotWidth = x2 - x1;
      for (let x = 0; x < slotWidth; x++) {
        let hasActive = false;
        for (let y = 0; y < candRaw.length; y++) {
          if (candRaw[y][x] === 1) {
            hasActive = true;
            break;
          }
        }
        if (hasActive) {
          if (minX === -1) minX = x;
          maxX = x;
        }
      }
      if (minX === -1) return null;

      // Trim candidate
      const cand = [];
      for (let y = minY; y <= maxY; y++) {
        cand.push(candRaw[y].subarray(minX, maxX + 1));
      }

      const ch = cand.length;
      const cw = ch > 0 ? cand[0].length : 0;
      if (ch === 0 || cw === 0) return null;

      let bestDigit = "0";
      let bestIou = -1.0;

      // Compare with templates 0-9
      for (const [digit, templ] of Object.entries(this.templates)) {
        const th = templ.length;
        const tw = th > 0 ? templ[0].length : 0;
        let scoreMax = 0.0;

        for (let dy = -2; dy <= 2; dy++) {
          for (let dx = -2; dx <= 2; dx++) {
            let inter = 0;
            let union = 0;
            const maxYBound = Math.max(ch, th);
            const maxXBound = Math.max(cw, tw);

            for (let y = 0; y < maxYBound; y++) {
              for (let x = 0; x < maxXBound; x++) {
                const v1 = (y < ch && x < cw) ? cand[y][x] : 0;
                const ty = y + dy;
                const tx = x + dx;
                const v2 = (ty >= 0 && ty < th && tx >= 0 && tx < tw) ? templ[ty][tx] : 0;

                if (v1 === 1 && v2 === 1) inter++;
                if (v1 === 1 || v2 === 1) union++;
              }
            }

            const iou = union > 0 ? inter / union : 0.0;
            if (iou > scoreMax) {
              scoreMax = iou;
            }
          }
        }

        if (scoreMax > bestIou) {
          bestIou = scoreMax;
          bestDigit = digit;
        }
      }

      predicted += bestDigit;
      scores.push(bestIou);
    }

    const avgConf = scores.length > 0 ? scores.reduce((a, b) => a + b, 0) / scores.length : 0;
    return { code: predicted, confidence: Math.round(avgConf * 100) / 100 };
  }

  _blobToImage(blob) {
    return new Promise((resolve, reject) => {
      const url = URL.createObjectURL(blob);
      const img = new Image();
      img.onload = () => {
        URL.revokeObjectURL(url);
        resolve(img);
      };
      img.onerror = (e) => {
        URL.revokeObjectURL(url);
        reject(e);
      };
      img.src = url;
    });
  }
}

// Global instance
window.captchaOcr = new CaptchaOCR();
