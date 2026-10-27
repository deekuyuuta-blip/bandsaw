// ===== 帯鋸 打音監視 判定コア (ブラウザ/Node 共通) =====
const BandsawCore = (function () {
  "use strict";

  // ---- 2次IIR (RBJ) ----
  function biquad(type, f, Q, sr) {
    const w = 2 * Math.PI * f / sr, c = Math.cos(w), s = Math.sin(w), a = s / (2 * Q);
    let b0, b1, b2;
    if (type === "lp") { b0 = (1 - c) / 2; b1 = 1 - c; b2 = (1 - c) / 2; }
    else { b0 = (1 + c) / 2; b1 = -(1 + c); b2 = (1 + c) / 2; }
    const a0 = 1 + a, a1 = -2 * c, a2 = 1 - a;
    return { b0: b0 / a0, b1: b1 / a0, b2: b2 / a0, a1: a1 / a0, a2: a2 / a0, z1: 0, z2: 0 };
  }
  // 4次Butterworth = Q 0.5412 と 1.3066 の2段
  function makeBand(lo, hi, sr) {
    return [biquad("hp", lo, 0.5412, sr), biquad("hp", lo, 1.3066, sr),
            biquad("lp", hi, 0.5412, sr), biquad("lp", hi, 1.3066, sr)];
  }

  // ---- FFT (radix-2, in place) ----
  function fftPow(re, im) {
    const n = re.length;
    for (let i = 1, j = 0; i < n; i++) {
      let bit = n >> 1;
      for (; j & bit; bit >>= 1) j ^= bit;
      j ^= bit;
      if (i < j) { let t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
    }
    for (let len = 2; len <= n; len <<= 1) {
      const ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang), h = len >> 1;
      for (let i = 0; i < n; i += len) {
        let cr = 1, ci = 0;
        for (let k = 0; k < h; k++) {
          const a = i + k, b = a + h;
          const tr = re[b] * cr - im[b] * ci, ti = re[b] * ci + im[b] * cr;
          re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti;
          const nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr;
        }
      }
    }
    const p = new Float64Array(n / 2 + 1);
    for (let i = 0; i <= n / 2; i++) p[i] = re[i] * re[i] + im[i] * im[i];
    return p;
  }
  function median(arr) {
    const a = Float64Array.from(arr).sort(); const n = a.length;
    if (!n) return NaN;
    return n % 2 ? a[(n - 1) >> 1] : (a[n / 2 - 1] + a[n / 2]) / 2;
  }
  function pct(arr, q) {
    const a = Float64Array.from(arr).sort(); if (!a.length) return NaN;
    const i = (a.length - 1) * q, lo = Math.floor(i), hi = Math.ceil(i);
    return a[lo] + (a[hi] - a[lo]) * (i - lo);
  }

  const DEFAULTS = {
    bladeMm: 8350, wheelMm: 1200, rpmMin: 720, rpmMax: 830,
    bandLo: 1500, bandHi: 4000, windowSec: 8.5, hopSec: 0.5, idleDb: -60,
    avgSec: 30, lockRatio: 0.7,
    warnAvg: 8, alarmAvg: 11, alarmInst: 14, alarmHold: 3,
    riseWarn: 4, riseAlarm: 7, riseInst: 5,
    learnMin: 10, learnAlarmAvg: 16,
    clearHold: 240
  };

  function bladeRange(c) {
    const circ = Math.PI * c.wheelMm / 1000, L = c.bladeMm / 1000;
    return [c.rpmMin / 60 * circ / L, c.rpmMax / 60 * circ / L];
  }

  // ---- 検出器: 音声サンプルを入れると hop ごとに結果を返す ----
  function Detector(cfg, sr) {
    const c = Object.assign({}, DEFAULTS, cfg);
    const B = Math.max(1, Math.round(sr / 200)), envFs = sr / B;
    const winN = Math.round(c.windowSec * envFs), hopN = Math.round(c.hopSec * envFs);
    const filt = makeBand(c.bandLo, c.bandHi, sr);
    const env = new Float64Array(winN); let envLen = 0, envPos = 0;
    let acc = 0, accN = 0, hopCnt = 0, fullAcc = 0, peak = 0, fullN = 0;
    const fullHist = [];
    const NFFT = 16384, fr = envFs / NFFT;
    const [fmin, fmax] = bladeRange(c);
    const hann = new Float64Array(winN);
    const out = [];

    function prominence(P, f) {
      let pk = 0; const nb = [];
      const i0 = Math.max(1, Math.floor((f - 1.5) / fr)), i1 = Math.min(P.length - 1, Math.ceil((f + 1.5) / fr));
      for (let i = i0; i <= i1; i++) {
        const d = Math.abs(i * fr - f);
        if (d <= 0.1) pk = Math.max(pk, P[i]);
        else if (d > 0.25 && d < 1.5) nb.push(P[i]);
      }
      return 10 * Math.log10(pk / (median(nb) + 1e-30) + 1e-30);
    }

    function analyze() {
      // 時系列順に取り出す
      const n = winN, e = new Float64Array(n);
      for (let i = 0; i < n; i++) e[i] = Math.log(env[(envPos + i) % n] + 1e-6);
      // 2Hz未満の変化を除去 (移動平均を引く, same)
      const k2 = Math.floor(envFs / 2), half = Math.floor(k2 / 2);
      const cs = new Float64Array(n + 1);
      for (let i = 0; i < n; i++) cs[i + 1] = cs[i] + e[i];
      const t0 = Math.floor(envFs / 4), m = n - 2 * t0;
      if (hann.length !== m) { }
      const re = new Float64Array(NFFT), im = new Float64Array(NFFT);
      for (let j = 0; j < m; j++) {
        const i = j + t0;
        const a = Math.max(0, i - half), b = Math.min(n, i - half + k2);
        const ma = (cs[b] - cs[a]) / k2;
        const w = 0.5 - 0.5 * Math.cos(2 * Math.PI * j / (m - 1));
        re[j] = (e[i] - ma) * w;
      }
      const P = fftPow(re, im);
      let best = -1, f0 = fmin;
      for (let i = Math.ceil(fmin / fr); i <= Math.floor(fmax / fr); i++) if (P[i] > best) { best = P[i]; f0 = i * fr; }
      const p1 = prominence(P, f0), p2 = prominence(P, 2 * f0);
      return { freq: f0, p1, p2, score: (p1 + p2) / 2 };
    }

    // x: Float32Array
    this.push = function (x) {
      for (let i = 0; i < x.length; i++) {
        let v = x[i];
        const a = Math.abs(v); if (a > peak) peak = a;
        fullAcc += v * v; fullN++;
        for (let k = 0; k < 4; k++) {
          const f = filt[k];
          const y = f.b0 * v + f.z1; f.z1 = f.b1 * v - f.a1 * y + f.z2; f.z2 = f.b2 * v - f.a2 * y; v = y;
        }
        acc += v * v; accN++;
        if (accN === B) {
          env[envPos] = Math.sqrt(acc / B + 1e-12); envPos = (envPos + 1) % winN;
          if (envLen < winN) envLen++;
          acc = 0; accN = 0; hopCnt++;
          if (hopCnt === hopN) {
            hopCnt = 0;
            let s2 = 0; for (let j = 0; j < winN; j++) s2 += env[j] * env[j];
            const bandDb = envLen ? 20 * Math.log10(Math.sqrt(s2 / envLen) + 1e-9) : -120;
            const fullDb = 20 * Math.log10(Math.sqrt(fullAcc / Math.max(1, fullN)) + 1e-9);
            const prev = fullHist.length >= 4 ? median(fullHist) : null;
            fullHist.push(fullDb); if (fullHist.length > Math.round(5 / c.hopSec)) fullHist.shift();
            const r = { bandDb, fullDb, peak, jumpDb: prev === null ? 0 : fullDb - prev,
                        ready: envLen >= winN, freq: NaN, score: NaN };
            if (r.ready) Object.assign(r, analyze());
            out.push(r);
            fullAcc = 0; fullN = 0; peak = 0;
          }
        }
      }
      return out.splice(0);
    };
    this.envFs = envFs; this.range = [fmin, fmax];
  }

  // ---- 判定 (帯鋸ごとの基準つき) ----
  // blade: {learning:bool, hops:[], inst:[], base:{p90,inst99,med}|null}
  function Judge(cfg, blade) {
    const c = Object.assign({}, DEFAULTS, cfg);
    const histN = Math.max(2, Math.round(c.avgSec / c.hopSec));
    const hs = [], hf = [];
    let state = "NORMAL", nAlarm = 0, nClear = 0, lastF = null, breakCool = 0;
    this.blade = blade || { learning: false, avgs: [], inst: [], base: null };
    const learnNeed = Math.round(c.learnMin * 60 / c.hopSec);

    this.thresholds = () => {
      const b = this.blade.base;
      if (!b) return { warn: c.warnAvg, alarmAvg: c.alarmAvg, inst: c.alarmInst, mode: this.blade.learning ? "learning" : "fixed" };
      return { warn: Math.max(c.warnAvg, b.p90 + c.riseWarn), alarmAvg: Math.max(c.alarmAvg, b.p90 + c.riseAlarm),
               inst: Math.max(c.alarmInst, b.inst99 + c.riseInst), mode: "blade" };
    };
    this.state = () => state;
    this.startLearning = () => { this.blade = { learning: true, avgs: [], inst: [], base: null, started: Date.now() }; state = "NORMAL"; nClear = 0; nAlarm = 0; };
    this.learnProgress = () => this.blade.learning ? Math.min(1, this.blade.avgs.length / learnNeed) : (this.blade.base ? 1 : 0);

    this.update = (r) => {
      const ev = [];
      breakCool = Math.max(0, breakCool - 1);
      if (r.jumpDb >= 8 && r.peak >= 0.9 && breakCool === 0) {
        ev.push(state === "NORMAL" ? "LOUD" : "BREAK"); breakCool = Math.round(10 / c.hopSec);
      }
      if (!r.ready || isNaN(r.score) || r.bandDb < c.idleDb) { r.state = state === "NORMAL" ? "IDLE" : state; return ev; }
      const s = r.score;
      hs.push(s); hf.push(r.freq); if (hs.length > histN) { hs.shift(); hf.shift(); }
      const full = hs.length >= histN * 0.8;
      const avg = hs.reduce((a, b) => a + b, 0) / hs.length;
      const mf = median(hf); let lk = 0; for (const f of hf) if (Math.abs(f - mf) <= 0.06) lk++;
      const lock = lk / hf.length;
      r.avg = avg; r.lock = lock;
      const sustained = full && lock >= c.lockRatio;

      // 学習中: 平均スコアを集める。極端に強い打音だけは警報
      const bl = this.blade;
      if (bl.learning) {
        if (full) { bl.avgs.push(avg); bl.inst.push(s); }
        if (bl.avgs.length >= learnNeed) {
          bl.base = { p90: pct(bl.avgs, 0.9), inst99: pct(bl.inst, 0.99), med: median(bl.avgs), at: Date.now() };
          bl.learning = false; bl.avgs = []; bl.inst = [];
          ev.push(bl.base.p90 >= c.warnAvg ? "BASE_HIGH" : "LEARNED");
        }
      }
      const th = this.thresholds();
      if (lastF !== null && Math.abs(r.freq - lastF) > 0.1) nAlarm = 0;
      lastF = r.freq;
      let next = state;
      if (bl.learning) {
        if (sustained && avg >= c.learnAlarmAvg) next = "ALARM";
      } else {
        nAlarm = s >= th.inst ? nAlarm + 1 : 0;
        if (nAlarm >= c.alarmHold || (sustained && avg >= th.alarmAvg)) next = "ALARM";
        else if (sustained && avg >= th.warn && state === "NORMAL") next = "WARN";
      }
      if (state !== "NORMAL" && full && avg < th.warn - 3) {
        if (++nClear >= c.clearHold) { next = "NORMAL"; nClear = 0; ev.push("CLEAR"); }
      } else nClear = 0;
      const L = ["NORMAL", "WARN", "ALARM"];
      if (L.indexOf(next) > L.indexOf(state)) ev.push(next);
      state = next;
      r.state = state; r.th = th;
      return ev;
    };
    this.reset = () => { state = "NORMAL"; nAlarm = 0; nClear = 0; };
  }

  return { Detector, Judge, DEFAULTS, bladeRange, median, pct };
})();
if (typeof module !== "undefined") module.exports = BandsawCore;
