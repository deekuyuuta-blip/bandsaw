#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
帯鋸 破断予兆 監視ソフト

帯鋸にき裂が入ると、き裂部がガイドやホイールを通るたびに「カチッ」と鳴る。
この音は「帯鋸1周 = 全長 ÷ 鋸速度」の周期で規則正しく繰り返す。本ソフトは
打音の強弱を周波数分析し、帯鋸1周の周波数とその2倍に鋭い山が出ているかを
スコア(dB)にする。健全な帯鋸では山は出ない (2026/10/27 平常時で最大約7dB、
破断前の帯鋸で15〜28dB)。しきい値を超えたら注意(WARN)・警報(ALARM)を出す。
破断音(BREAK)も記録する。1分ごとの傾向を logs/trend.csv に残す。

使い方:
  python bandsaw_monitor.py run                 … 常時監視 (config.ini の source から入力)
  python bandsaw_monitor.py analyze 録画.mp4     … 録画ファイルを検証 (CSV とグラフを出力)
  python bandsaw_monitor.py learn  平常.mp4      … 平常運転の録音から平常時スコアを測る
  共通オプション: -c 設定ファイル (既定: config.ini)

必要なもの: Python 3.9+, numpy, scipy, ffmpeg  (グラフ出力のみ matplotlib)
"""
import argparse
import configparser
import csv
import datetime as dt
import json
import os
import shlex
import subprocess
import sys
import threading
import time
import urllib.request
import wave
from collections import deque

import numpy as np
from scipy.signal import butter, sosfilt, sosfilt_zi

SR = 16000          # 解析サンプリング周波数
ENV_DEC = 80        # 包絡線の間引き (16000/80 = 200 Hz)
ENV_FS = SR // ENV_DEC


# ------------------------------------------------------------------ 設定
def load_config(path):
    cp = configparser.ConfigParser(inline_comment_prefixes=(";",))
    with open(path, encoding="utf-8-sig") as f:   # メモ帳のBOM付きUTF-8にも対応
        cp.read_file(f)
    g = lambda s, k, d=None: cp.get(s, k, fallback=d).strip()
    c = {
        "blade_mm": float(g("machine", "blade_length_mm")),
        "wheel_mm": float(g("machine", "wheel_diameter_mm")),
        "rpm_min": float(g("machine", "wheel_rpm_min")),
        "rpm_max": float(g("machine", "wheel_rpm_max")),
        "source": g("input", "source", ""),
        "input_format": g("input", "input_format", ""),
        "ffmpeg": g("input", "ffmpeg", "ffmpeg") or "ffmpeg",
        "band_lo": float(g("detect", "band_low_hz", "1500")),
        "band_hi": float(g("detect", "band_high_hz", "4000")),
        "window": float(g("detect", "window_sec", "8.5")),
        "hop": float(g("detect", "hop_sec", "0.5")),
        "idle_db": float(g("detect", "idle_level_db", "-45")),
        "avg_sec": float(g("alarm", "avg_sec", "30")),
        "warn_avg": float(g("alarm", "warn_avg_score", "8")),
        "alarm_avg": float(g("alarm", "alarm_avg_score", "11")),
        "lock_ratio": float(g("alarm", "lock_ratio", "0.7")),
        "alarm": float(g("alarm", "alarm_score", "14")),
        "alarm_hold": int(g("alarm", "alarm_hold", "3")),
        "baseline": g("alarm", "baseline_score", ""),
        "min_rise": float(g("alarm", "min_rise", "4")),
        "clear_hold": int(g("alarm", "clear_hold", "240")),
        "cmd_warn": g("action", "command_warn", ""),
        "cmd_alarm": g("action", "command_alarm", ""),
        "webhook": g("action", "webhook_url", ""),
        "beep": g("action", "beep", "no").lower() in ("yes", "true", "1", "on"),
        "save_sec": float(g("action", "save_audio_sec", "30")),
        "log_dir": g("log", "dir", "logs"),
    }
    c["baseline"] = float(c["baseline"]) if c["baseline"] else None
    return c


def blade_freq_range(c):
    """帯鋸1周の周波数範囲 (Hz) = 鋸速度 / 全長"""
    circ = np.pi * c["wheel_mm"] / 1000.0
    L = c["blade_mm"] / 1000.0
    return c["rpm_min"] / 60 * circ / L, c["rpm_max"] / 60 * circ / L


# ------------------------------------------------------------------ 入力 (ffmpeg)
def open_ffmpeg(c, source, input_format=""):
    cmd = [c["ffmpeg"], "-hide_banner", "-loglevel", "error", "-nostdin"]
    if source.lower().startswith("rtsp://"):
        cmd += ["-rtsp_transport", "tcp"]
    if input_format:
        cmd += ["-f", input_format]
    cmd += ["-i", source, "-vn", "-ac", "1", "-ar", str(SR), "-f", "s16le", "-"]
    return subprocess.Popen(cmd, stdout=subprocess.PIPE, bufsize=0)


def read_blocks(proc, n):
    """n サンプルずつ float 配列を返す。終わったら None"""
    need = n * 2
    buf = b""
    while True:
        chunk = proc.stdout.read(need - len(buf))
        if not chunk:
            return
        buf += chunk
        if len(buf) == need:
            yield np.frombuffer(buf, np.int16).astype(np.float32) / 32768.0
            buf = b""


# ------------------------------------------------------------------ 検出器
class Detector:
    def __init__(self, c):
        self.c = c
        self.fmin, self.fmax = blade_freq_range(c)
        self.sos = butter(4, [c["band_lo"], c["band_hi"]], "bandpass", fs=SR, output="sos")
        self.zi = sosfilt_zi(self.sos) * 0.0
        self.hop_n = int(round(c["hop"] * SR))
        self.win_env = int(round(c["window"] * ENV_FS))
        self.env = deque(maxlen=self.win_env)
        self.rest = np.zeros(0, np.float32)
        self.full_lvl = deque(maxlen=int(5 / c["hop"]))   # 直近5秒の全帯域レベル
        self.nfft = 16384

    def push(self, x):
        """hop_sec 分の音声を入れて判定結果 dict を返す"""
        y, self.zi = sosfilt(self.sos, x, zi=self.zi)
        y = np.concatenate([self.rest, y.astype(np.float32)])
        k = len(y) // ENV_DEC
        self.rest = y[k * ENV_DEC:]
        e = np.sqrt((y[: k * ENV_DEC].reshape(k, ENV_DEC) ** 2).mean(axis=1) + 1e-12)
        self.env.extend(e)

        # 打音帯域の音量は判定窓全体 (window_sec) の平均で見る
        band_db = 20 * np.log10(float(np.sqrt(np.mean(np.square(self.env)))) + 1e-9) if self.env else -120.0
        full_db = 20 * np.log10(np.sqrt(np.mean(x.astype(np.float64) ** 2)) + 1e-9)
        prev = np.median(self.full_lvl) if len(self.full_lvl) >= 4 else None
        self.full_lvl.append(full_db)
        peak = float(np.max(np.abs(x))) if len(x) else 0.0

        r = {"band_db": band_db, "full_db": full_db, "freq": np.nan, "score": np.nan,
             "ready": len(self.env) >= self.win_env,
             "jump_db": (full_db - prev) if prev is not None else 0.0, "peak": peak}
        if not r["ready"]:
            return r
        # 包絡線を対数にし、2Hz未満のゆっくりした強弱 (材の出入り・負荷変動) を取り除く
        ev = np.log(np.asarray(self.env, np.float64) + 1e-6)
        k2 = ENV_FS // 2
        ev = (ev - np.convolve(ev, np.ones(k2) / k2, "same"))[ENV_FS // 4: -ENV_FS // 4]
        F = np.abs(np.fft.rfft(ev * np.hanning(len(ev)), self.nfft)) ** 2
        fr = np.fft.rfftfreq(self.nfft, 1 / ENV_FS)
        band = (fr >= self.fmin) & (fr <= self.fmax)
        f0 = float(fr[band][np.argmax(F[band])])
        # 帯鋸1周の周波数 (基本) と その2倍 (2次) に、周りより鋭く突き出た山があるか (dB)
        p1, p2 = self._prominence(F, fr, f0), self._prominence(F, fr, 2 * f0)
        r["freq"], r["p1"], r["p2"] = f0, p1, p2
        r["score"] = (p1 + p2) / 2
        return r

    @staticmethod
    def _prominence(F, fr, f, excl=0.25, span=1.5):
        pk = np.abs(fr - f) <= 0.1
        nb = (np.abs(fr - f) > excl) & (np.abs(fr - f) < span)
        return float(10 * np.log10(F[pk].max() / (np.median(F[nb]) + 1e-30) + 1e-30))


# ------------------------------------------------------------------ 判定 (状態遷移)
class Judge:
    LEVELS = ["NORMAL", "WARN", "ALARM"]

    def __init__(self, c):
        self.c = c
        self.state = "NORMAL"
        self.n_warn = self.n_alarm = self.n_clear = 0
        self.baseline = c["baseline"]
        self.learn = deque(maxlen=int(600 / c["hop"]))  # 自動学習: 直近10分の平常スコア

    def base(self):
        if self.baseline is not None:
            return self.baseline
        if len(self.learn) >= int(30 / self.c["hop"]):
            return float(np.median(self.learn))
        return None

    def update(self, r):
        """戻り値: 新たに発生したイベント ('WARN'/'ALARM'/'BREAK'/'CLEAR') のリスト"""
        c, events = self.c, []
        # 破断音: 全帯域レベルが直近5秒の中央値より8dB以上跳ね上がり、ほぼ振り切れ
        self.break_cool = max(0, getattr(self, "break_cool", 0) - 1)
        if r["jump_db"] >= 8 and r["peak"] >= 0.9 and self.break_cool == 0:
            # 大きな音は材の切削でも出る。注意/警報中なら「破断音」、平常時は記録だけ
            events.append("BREAK" if self.state in ("WARN", "ALARM") else "LOUD")
            self.break_cool = int(10 / c["hop"])   # 10秒間は重複して出さない
        if not r["ready"] or np.isnan(r["score"]) or r["band_db"] < c["idle_db"]:
            r["state"] = self.state if self.state != "NORMAL" else "IDLE"
            r["baseline"] = self.base()
            return events
        s, b = r["score"], self.base()
        rise_ok = (b is None) or (s - b >= c["min_rise"])
        # 直近 avg_sec 秒の平均スコアと「周波数の安定度」
        #   き裂の打音: 周波数が帯鋸1周の値にほぼ固定 (安定度 1.0 付近)
        #   平常時    : 雑音の山なので周波数がふらつく (安定度 0.5 以下)
        if not hasattr(self, "hist"):
            self.hist = deque(maxlen=max(2, int(c["avg_sec"] / c["hop"])))
        self.hist.append((s, r["freq"]))
        hs = np.array([h[0] for h in self.hist])
        hf = np.array([h[1] for h in self.hist])
        full = len(self.hist) >= self.hist.maxlen * 0.8
        avg = float(hs.mean())
        lock = float(np.mean(np.abs(hf - np.median(hf)) <= 0.06))
        r["avg"], r["lock"] = avg, lock
        sustained = full and lock >= c["lock_ratio"]
        # 瞬時の強い打音: 周波数が前回から0.1Hz以上ずれたら連続カウントをやり直す
        last = getattr(self, "last_f", None)
        self.last_f = r["freq"]
        if last is not None and abs(r["freq"] - last) > 0.1:
            self.n_alarm = 0
        self.n_alarm = self.n_alarm + 1 if (s >= c["alarm"] and rise_ok) else 0
        new = self.state
        if self.n_alarm >= c["alarm_hold"] or (sustained and avg >= c["alarm_avg"]):
            new = "ALARM"
        elif sustained and avg >= c["warn_avg"] and self.state == "NORMAL":
            new = "WARN"
        # 解除: 平均スコアが十分低い状態 (注意値-3dB未満) が clear_hold 回続いたとき
        if self.state != "NORMAL" and full and avg < c["warn_avg"] - 3:
            self.n_clear += 1
            if self.n_clear >= c["clear_hold"]:
                new, self.n_clear = "NORMAL", 0
                events.append("CLEAR")
        else:
            self.n_clear = 0
        if self.LEVELS.index(new) > self.LEVELS.index(self.state):
            events.append(new)
        self.state = new
        if self.state == "NORMAL" and s < c["alarm"]:
            self.learn.append(s)
        r["state"], r["baseline"] = self.state, b
        return events


# ------------------------------------------------------------------ 発報
class Actions:
    def __init__(self, c, live):
        self.c, self.live = c, live
        os.makedirs(c["log_dir"], exist_ok=True)
        self.audio = deque(maxlen=max(1, int(c["save_sec"] / c["hop"])))

    def keep_audio(self, x):
        if self.c["save_sec"] > 0:
            self.audio.append((x * 32767).astype(np.int16))

    def fire(self, level, r, when):
        msg = {"WARN": "【注意】帯鋸1周ごとの打音あり (き裂の疑い) 次の停止で帯鋸を点検・交換",
               "ALARM": "【警報】帯鋸1周ごとの打音が強い (き裂進行) できるだけ早く停止・交換",
               "BREAK": "【破断音】注意/警報中に大きな異常音を検出",
               "LOUD": "(記録) 大きな音",
               "CLEAR": "【解除】周期打音が平常に戻りました"}[level]
        line = f"{when:%Y-%m-%d %H:%M:%S.%f}"[:-5] + f"  {msg}  score={r.get('score', float('nan')):.1f}dB"
        print(line, flush=True)
        with open(os.path.join(self.c["log_dir"], "events.log"), "a", encoding="utf-8") as f:
            f.write(line + "\n")
        if not self.live or level in ("CLEAR", "LOUD"):
            return
        cmd = self.c["cmd_alarm"] if level in ("ALARM", "BREAK") else self.c["cmd_warn"]
        if cmd:
            cmd = cmd.format(level=level, score=f"{r.get('score', 0):.2f}", time=f"{when:%H:%M:%S}")
            try:
                subprocess.Popen(cmd, shell=True)
            except Exception as e:
                print("  コマンド実行失敗:", e)
        if self.c["webhook"]:
            body = json.dumps({"level": level, "message": msg, "score": r.get("score"),
                               "time": when.isoformat(), "text": line}, ensure_ascii=False).encode()
            threading.Thread(target=self._post, args=(body,), daemon=True).start()
        if self.c["beep"] and os.name == "nt":
            import winsound
            threading.Thread(target=lambda: [winsound.Beep(2000 if level != "WARN" else 1200, 400)
                                             for _ in range(3 if level != "WARN" else 1)],
                             daemon=True).start()
        if self.c["save_sec"] > 0 and self.audio and level in ("WARN", "ALARM", "BREAK"):
            fn = os.path.join(self.c["log_dir"], f"{when:%Y%m%d_%H%M%S}_{level}.wav")
            with wave.open(fn, "wb") as w:
                w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
                w.writeframes(np.concatenate(self.audio).tobytes())

    def _post(self, body):
        try:
            req = urllib.request.Request(self.c["webhook"], data=body,
                                         headers={"Content-Type": "application/json"})
            urllib.request.urlopen(req, timeout=5).read()
        except Exception as e:
            print("  Webhook 送信失敗:", e)


# ------------------------------------------------------------------ 処理ループ
def process(c, source, input_format, live, start_time=None, on_row=None):
    det, judge, act = Detector(c), Judge(c), Actions(c, live)
    fmin, fmax = blade_freq_range(c)
    print(f"帯鋸1周の周波数: {fmin:.2f}〜{fmax:.2f} Hz  (周期 {1/fmax:.3f}〜{1/fmin:.3f} 秒)")
    proc = open_ffmpeg(c, source, input_format)
    t = 0.0
    for x in read_blocks(proc, det.hop_n):
        t += len(x) / SR
        when = dt.datetime.now() if live else (start_time or dt.datetime(2000, 1, 1)) + dt.timedelta(seconds=t)
        act.keep_audio(x)
        r = det.push(x)
        events = judge.update(r)
        r["t"], r["when"] = t, when
        for ev in events:
            act.fire(ev, r, when)
        if on_row:
            on_row(r)
    proc.wait()
    return proc.returncode


def cmd_run(c, args):
    if not c["source"]:
        sys.exit("config.ini の [input] source を設定してください")
    os.makedirs(c["log_dir"], exist_ok=True)
    csv_path = os.path.join(c["log_dir"], f"score_{dt.datetime.now():%Y%m%d}.csv")
    print("監視開始  (Ctrl+C で終了)")
    last_print = [0.0]
    minute = {"key": None, "s": [], "state": "NORMAL"}

    def write_trend(key, scores, state):
        # 1分ごとの要約 (長期の傾向を見る用)。運転中の分だけ記録
        if not scores:
            return
        p = os.path.join(c["log_dir"], "trend.csv")
        new = not os.path.exists(p)
        with open(p, "a", newline="", encoding="utf-8-sig") as f:
            w = csv.writer(f)
            if new:
                w.writerow(["分", "中央値dB", "最大dB", "判定数", "最悪状態"])  # 中央値が6dBを超え始めたら要注意
            w.writerow([key, f"{np.median(scores):.1f}", f"{np.max(scores):.1f}", len(scores), state])

    def on_row(r):
        nonlocal csv_path
        key = f"{r['when']:%Y-%m-%d %H:%M}"
        if key != minute["key"]:
            write_trend(minute["key"], minute["s"], minute["state"])
            minute.update(key=key, s=[], state="NORMAL")
        if r.get("state") in ("NORMAL", "WARN", "ALARM") and not np.isnan(r["score"]):
            minute["s"].append(r["score"])
            order = ["NORMAL", "WARN", "ALARM"]
            if order.index(r["state"]) > order.index(minute["state"]):
                minute["state"] = r["state"]
        p = os.path.join(c["log_dir"], f"score_{r['when']:%Y%m%d}.csv")
        new = not os.path.exists(p)
        with open(p, "a", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            if new:
                w.writerow(["time", "state", "score_db", "freq_hz", "band_db", "full_db"])
            w.writerow([f"{r['when']:%H:%M:%S.%f}"[:-5], r.get("state"), f"{r['score']:.1f}",
                        f"{r['freq']:.2f}", f"{r['band_db']:.1f}", f"{r['full_db']:.1f}"])
        if r["t"] - last_print[0] >= 5:
            last_print[0] = r["t"]
            print(f"  {r['when']:%H:%M:%S}  状態={r.get('state')}  スコア={r['score']:.1f}dB"
                  f"  平均={r.get('avg', float('nan')):.1f}dB  安定度={r.get('lock', float('nan')):.2f}"
                  f"  周期={r['freq']:.2f}Hz  レベル={r['band_db']:.0f}dB", flush=True)

    while True:   # 接続が切れたら再接続
        try:
            process(c, c["source"], c["input_format"], live=True, on_row=on_row)
        except KeyboardInterrupt:
            print("終了します")
            return
        print(f"{dt.datetime.now():%H:%M:%S}  入力が途切れました。10秒後に再接続します…", flush=True)
        try:
            time.sleep(10)
        except KeyboardInterrupt:
            return


def cmd_analyze(c, args):
    rows = []
    start = dt.datetime.fromisoformat(args.start) if args.start else None
    c = dict(c, beep=False, save_sec=0, log_dir=args.out)
    process(c, args.file, "", live=False, start_time=start, on_row=rows.append)
    os.makedirs(args.out, exist_ok=True)
    base = os.path.splitext(os.path.basename(args.file))[0]
    p = os.path.join(args.out, base + "_score.csv")
    with open(p, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["秒", "時刻", "状態", "スコアdB", "平均dB", "周波数安定度", "周期Hz", "打音帯域dB", "全体dB"])
        for r in rows:
            w.writerow([f"{r['t']:.1f}", f"{r['when']:%H:%M:%S.%f}"[:-5], r.get("state"),
                        f"{r['score']:.1f}", f"{r.get('avg', float('nan')):.1f}", f"{r.get('lock', float('nan')):.2f}", f"{r['freq']:.2f}", f"{r['band_db']:.1f}", f"{r['full_db']:.1f}"])
    print("CSV:", p)
    try:
        plot(c, rows, os.path.join(args.out, base + "_score.png"), start is not None)
    except ImportError:
        print("(matplotlib が無いのでグラフは省略)")


def cmd_learn(c, args):
    rows = []
    c = dict(c, beep=False, save_sec=0, warn_avg=999, alarm_avg=999, alarm=999)
    src = args.file or c["source"]
    fmt = "" if args.file else c["input_format"]
    if not args.file:
        print(f"{args.minutes} 分間、平常運転の音を測ります…")
        t_end = time.time() + args.minutes * 60

        def on_row(r):
            rows.append(r)
            if time.time() > t_end:
                raise KeyboardInterrupt
        try:
            process(c, src, fmt, live=False, on_row=on_row)
        except KeyboardInterrupt:
            pass
    else:
        process(c, src, fmt, live=False, on_row=rows.append)
    s = np.array([r["score"] for r in rows if r["ready"] and r["band_db"] >= c["idle_db"]
                  and not np.isnan(r["score"])])
    if len(s) < 20:
        sys.exit("運転中のデータが少なすぎます (idle_level_db を下げるか、長めに録音してください)")
    med, p95, p99 = np.median(s), np.percentile(s, 95), np.percentile(s, 99)
    W = max(2, int(c["avg_sec"] / c["hop"]))
    av = np.convolve(s, np.ones(W) / W, "valid") if len(s) >= W else s
    print(f"\n運転中データ {len(s)} 点")
    print(f"  瞬時スコア(dB)  中央値 {med:.1f} / 99% {p99:.1f} / 最大 {s.max():.1f}")
    print(f"  {c['avg_sec']:.0f}秒平均(dB)   最大 {av.max():.1f}")
    if av.max() >= 7:
        print("\n ※注意: 平常時としては平均スコアが高めです。き裂のある帯鋸の音や、")
        print("   周期的な別の音 (送り装置・コンベア等) が入っていないか確認してください。")
    print("\n config.ini [alarm] の推奨値:")
    print(f"   baseline_score  = {med:.1f}")
    print(f"   warn_avg_score  = {max(8.0, round(av.max() + 2, 1)):.1f}")
    print(f"   alarm_avg_score = {max(11.0, round(av.max() + 5, 1)):.1f}")
    print(f"   alarm_score     = {max(14.0, round(s.max() + 3, 1)):.1f}")
    print(" ※ 交換直後の帯鋸で、数日分の録音を測るのが理想です。")


def plot(c, rows, path, use_clock):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib import font_manager
    for name in ("Noto Sans CJK JP", "Yu Gothic", "Meiryo", "MS Gothic", "IPAexGothic"):
        if any(name in f.name for f in font_manager.fontManager.ttflist):
            plt.rcParams["font.family"] = name
            break
    xs = [r["when"] if use_clock else r["t"] for r in rows]
    sc = [r["score"] for r in rows]
    db = [r["full_db"] for r in rows]
    fig, (a1, a2) = plt.subplots(2, 1, figsize=(11, 6), sharex=True,
                                 gridspec_kw={"height_ratios": [3, 1.4]})
    av = [r.get("avg", np.nan) for r in rows]
    a1.plot(xs, sc, color="#9db7e0", lw=1, label="打音スコア (瞬時)")
    a1.plot(xs, av, color="#1f5fbf", lw=2.2, label=f"打音スコア ({c['avg_sec']:.0f}秒平均)")
    a1.axhline(c["warn_avg"], color="#e0a000", ls="--", lw=1.2, label=f"注意 平均{c['warn_avg']:g}dB")
    a1.axhline(c["alarm_avg"], color="#d62728", ls=":", lw=1.2, label=f"警報 平均{c['alarm_avg']:g}dB")
    a1.axhline(c["alarm"], color="#d62728", ls="--", lw=1.2, label=f"警報 瞬時{c['alarm']:g}dB")
    shown = set()
    for r, x in zip(rows, xs):
        st = r.get("state")
        if st in ("WARN", "ALARM") and st not in shown:
            shown.add(st)
            col = "#e0a000" if st == "WARN" else "#d62728"
            a1.axvline(x, color=col, lw=2, alpha=.6)
            a1.annotate("注意" if st == "WARN" else "警報", (x, 0.97), xycoords=("data", "axes fraction"), color=col, fontsize=11,
                        ha="left", va="top", xytext=(3, 0), textcoords="offset points", weight="bold")
    brk = [x for r, x in zip(rows, xs) if r["jump_db"] >= 8 and r["peak"] >= 0.9 and r.get("state") in ("WARN", "ALARM")]
    for x in brk[:1]:
        for a in (a1, a2):
            a.axvline(x, color="k", lw=2, alpha=.7)
        a1.annotate("破断音", (x, 0.97), xycoords=("data", "axes fraction"), fontsize=11, ha="right", va="top", weight="bold",
                    xytext=(-3, 0), textcoords="offset points")
    a1.set_ylim(min(0, np.nanmin(sc) - 1 if len(sc) else 0), max(32, np.nanmax(sc) + 2 if len(sc) else 32))
    a1.set_ylabel("スコア (dB)")
    a1.legend(loc="center left", fontsize=9)
    a1.set_title("帯鋸 打音スコア (帯鋸1周の周波数に出る山の高さ)")
    a1.grid(alpha=.3)
    a2.plot(xs, db, color="#555", lw=1.5)
    a2.set_ylabel("音量 dBFS")
    a2.set_xlabel("時刻" if use_clock else "秒")
    a2.grid(alpha=.3)
    if use_clock:
        import matplotlib.dates as md
        a2.xaxis.set_major_formatter(md.DateFormatter("%H:%M:%S"))
    fig.tight_layout()
    fig.savefig(path, dpi=130)
    print("グラフ:", path)


def main():
    ap = argparse.ArgumentParser(description="帯鋸 破断予兆 監視ソフト")
    ap.add_argument("-c", "--config", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.ini"))
    sub = ap.add_subparsers(dest="mode", required=True)
    sub.add_parser("run", help="常時監視")
    a = sub.add_parser("analyze", help="録画ファイルを検証")
    a.add_argument("file")
    a.add_argument("--start", help="録画の開始時刻 例: 2026-10-27T13:08:21.5")
    a.add_argument("--out", default="analyze_out")
    l = sub.add_parser("learn", help="平常時スコアを測る")
    l.add_argument("file", nargs="?", help="平常運転の録画/録音 (省略時は source から測定)")
    l.add_argument("--minutes", type=float, default=10)
    args = ap.parse_args()
    c = load_config(args.config)
    {"run": cmd_run, "analyze": cmd_analyze, "learn": cmd_learn}[args.mode](c, args)


if __name__ == "__main__":
    main()
