package com.tangran.tangrantech.native_scripts.daofa.boss;

import com.tangran.tangrantech.TT;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * BOSS战 的音符盒 BGM。
 *
 * <h2>当前进度</h2>
 * <ul>
 *   <li><b>一阶段（Scarface 循环）✅ 已实现</b> —— {@link #startPhaseOne} / {@link #stopPhaseOne}。
 *       它只有两张 32 项的音高表 + 每 4 拍一个音符，完全自包含，且<b>一阶段倒计时结束时就要播</b>，
 *       所以随一阶段一起做。</li>
 *   <li><b>二阶段（FEVER_PITCH.json 等）⬜ 批次 9</b> —— 那套要从 {@code addons/} 读 MIDI 转换出的
 *       JSON、还要在数据缺失时退回内置循环，单独一批做。这里先把
 *       {@link #startPhaseTwo} / {@link #stopPhaseTwo} 两个入口留好（当前如实降级为不播）。</li>
 * </ul>
 *
 * <h2>为什么用音符盒音效而不是真的放音符盒方块</h2>
 * <p>原脚本就是<b>在世界中心循环播放音符盒音效</b>（{@code block.note_block.bit} 等），
 * 一个方块都不放。这样既不需要在场地里摆 25 个音符盒，也不会被玩家挖掉。</p>
 */
public final class BossMusic {

    private BossMusic() {}

    /** 一阶段主旋律音高表（32 项，原脚本 {@code SCARFACE_MELODY}）。 */
    private static final double[] SCARFACE_MELODY = {
            1.0, 1.0, 1.1225, 1.0, 0.9439, 0.8909, 0.8409, 1.0,
            1.0, 1.0, 1.1892, 1.1225, 1.0, 0.9439, 0.8909, 0.8409,
            1.0, 1.1892, 1.3348, 1.1892, 1.0, 0.9439, 1.0, 1.1225,
            1.0, 0.8909, 0.8409, 0.8909, 1.0, 1.1225, 1.0, 0.9439,
    };

    /** 一阶段贝斯音高表（32 项，原脚本 {@code SCARFACE_BASS}）。 */
    private static final double[] SCARFACE_BASS = {
            0.5, 0.5, 0.5946, 0.5, 0.5, 0.5, 0.7071, 0.5,
            0.5, 0.5, 0.5946, 0.5, 0.5, 0.5, 0.7071, 0.5,
            0.5, 0.5, 0.5946, 0.5, 0.5, 0.5, 0.7071, 0.5,
            0.5, 0.5, 0.5946, 0.5, 0.5, 0.5, 0.7071, 0.5,
    };

    /** 每 4 刻一个音符（5 个/秒）。 */
    private static final long PHASE_ONE_PERIOD = 4L;

    /** 一阶段用到的四个音效名（停止时要按名停）。 */
    private static final String[] PHASE_ONE_SOUNDS = {
            "block.note_block.bit", "block.note_block.bass",
            "block.note_block.snare", "block.note_block.basedrum",
    };

    // ==================================================================
    // 一阶段
    // ==================================================================

    /**
     * 开始播一阶段 BGM。
     *
     * <p>节拍构成（照抄原脚本）：主旋律 + 贝斯每拍都响，<b>偶数拍加军鼓</b>、
     * <b>每 4 拍加底鼓</b>。任务自检「这一局还是当前战局吗」，战局一收就自停。</p>
     *
     * @param state 本局状态（任务句柄存在 {@code state.tangran.musicTask} 上）
     * @param center 播放中心（通常是场地中心）
     */
    public static void startPhaseOne(BossState state, Location center) {
        try {
            if (state == null || center == null || center.getWorld() == null) return;
            if (state.tangran.musicTask != null) return;      // 幂等
            int[] index = {0};
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(
                    TT.plugin, () -> {
                        try {
                            if (BossFight.byKey(state.key) != state
                                    || state.stand == null || !state.stand.isValid()) {
                                BossState.cancelTask(state.tangran.musicTask);
                                state.tangran.musicTask = null;
                                return;
                            }
                            int i = index[0] % SCARFACE_MELODY.length;
                            note(center, "block.note_block.bit", 1.0f, SCARFACE_MELODY[i]);
                            note(center, "block.note_block.bass", 0.9f, SCARFACE_BASS[i]);
                            if (i % 2 == 0) note(center, "block.note_block.snare", 0.65f, 1.0f);
                            if (i % 4 == 0) note(center, "block.note_block.basedrum", 0.9f, 0.8f);
                            index[0]++;
                        } catch (Throwable ignored) {
                            // 单个音符失败不影响整首曲子
                        }
                    }, 0L, PHASE_ONE_PERIOD);
            state.tangran.musicTask = task;
        } catch (Throwable t) {
            BossLog.warn("一阶段 BGM 启动失败: " + t);
        }
    }

    /** 停一阶段 BGM：取消任务 + 按名停掉四个音效（只停本局玩家那个，绝不清空全服）。 */
    public static void stopPhaseOne(BossState state) {
        try {
            if (state == null) return;
            BossState.cancelTask(state.tangran.musicTask);
            state.tangran.musicTask = null;
            Player player = (state.playerName == null)
                    ? null : Bukkit.getPlayerExact(state.playerName);
            if (player == null) return;
            for (String sound : PHASE_ONE_SOUNDS) {
                try {
                    player.stopSound(sound);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void note(Location center, String sound, float volume, double pitch) {
        try {
            center.getWorld().playSound(center, sound, volume, (float) pitch);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // ==================================================================
    // 二阶段（批次 9：FEVER_PITCH 内置循环 + MIDI JSON 曲目）
    // ==================================================================

    /** 二阶段主旋律音高表（32 项，原脚本 {@code FEVER_PITCH_MELODY}）。 */
    private static final double[] FEVER_PITCH_MELODY = {
            1.0, 1.1892, 1.3348, 1.1892, 1.0, 0.8909, 1.0, 1.1225,
            1.0, 1.1892, 1.3348, 1.4983, 1.3348, 1.1892, 1.0, 0.8909,
            1.0, 1.1892, 1.3348, 1.1892, 1.0, 1.1225, 1.1892, 1.3348,
            1.4983, 1.3348, 1.1892, 1.0, 0.8909, 0.8409, 0.8909, 1.0,
    };

    /** 二阶段贝斯音高表（32 项，原脚本 {@code FEVER_PITCH_BASS}）。 */
    private static final double[] FEVER_PITCH_BASS = {
            0.5, 0.5, 0.5946, 0.5, 0.5, 0.5946, 0.5, 0.5,
            0.5, 0.5, 0.5946, 0.5, 0.5, 0.5946, 0.5, 0.5,
            0.5, 0.5, 0.5946, 0.5, 0.5, 0.5946, 0.5, 0.5,
            0.5, 0.5, 0.5946, 0.5, 0.5, 0.7071, 0.5, 0.5,
    };

    /** 二阶段内置循环的周期（每 3 拍一个音符，比一阶段密）。 */
    private static final long PHASE_TWO_PERIOD = 3L;

    /** 二阶段用到的音效名（停止时要按名停）。 */
    private static final String[] PHASE_TWO_SOUNDS = {
            "block.note_block.bit", "block.note_block.bass", "block.note_block.bell",
            "block.note_block.harp", "block.note_block.hat",
            "block.note_block.snare", "block.note_block.basedrum",
    };

    /**
     * 二阶段 BGM：优先读 MIDI 转换出的 JSON 曲目，读不到退回内置 Fever Pitch 循环。
     *
     * <p>曲目 JSON 查找顺序（原脚本只找 RSC 目录，standalone 下两处都试）：
     * ① 本插件数据目录 {@code addons/糖然科技 TangranTech/scripts/道法/<file>}
     * ② 本插件数据目录 {@code 道法/<file>}。都没有 → 内置循环。</p>
     */
    public static void startPhaseTwo(BossState state, Location center, String songFile) {
        try {
            if (state == null) return;
            if (state.phaseTwo.musicTask != null) return;    // 幂等（原脚本同款）
            Player player = (state.playerName == null)
                    ? null : Bukkit.getPlayerExact(state.playerName);
            if (player == null || !player.isOnline()) return;
            String file = (songFile != null && !songFile.isEmpty())
                    ? songFile : state.phaseTwo.musicFile;
            state.phaseTwo.musicFile = file;
            Map<String, Object> data = loadSong(file);

            if (data == null || !(data.get("events") instanceof List<?> events)
                    || ((List<?>) events).isEmpty()) {
                startFeverFallback(state, player);
                return;
            }

            // MIDI 事件：[tick, 乐器序号, MIDI 音符号, 音量]
            List<Object> instruments = new java.util.ArrayList<>();
            if (data.get("instruments") instanceof List<?> il && !il.isEmpty()) {
                instruments.addAll(il);
            } else {
                instruments.add("block.note_block.bit");
                instruments.add("block.note_block.bass");
                instruments.add("block.note_block.bell");
                instruments.add("block.note_block.harp");
            }
            int length = 2665;
            Object lenObj = data.get("length");
            if (lenObj instanceof Number n && n.intValue() > 0) {
                length = n.intValue();
            }
            final int songLength = length;

            int[] index = {0};
            int[] tick = {0};
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(TT.plugin, () -> {
                try {
                    // 静态审查修正：每拍重取在线玩家——捕获开局引用的话，重连后
                    // isOnline() 永远 false，任务自取消且无重启路径，之后全程无声
                    Player p = online(state);
                    if (p == null || BossFight.byKey(state.key) != state) {
                        BossState.cancelTask(state.phaseTwo.musicTask);
                        state.phaseTwo.musicTask = null;
                        return;
                    }
                    int now = tick[0];
                    while (index[0] < events.size()) {
                        Object raw = events.get(index[0]);
                        if (!(raw instanceof List<?> ev) || ev.isEmpty()) {
                            index[0]++;
                            continue;
                        }
                        int evTick = (ev.get(0) instanceof Number n) ? n.intValue() : 0;
                        if (evTick > now) break;
                        int instIdx = (ev.size() > 1 && ev.get(1) instanceof Number n)
                                ? n.intValue() : 0;
                        double midi = (ev.size() > 2 && ev.get(2) instanceof Number n)
                                ? n.doubleValue() : 60.0d;
                        float vol = (ev.size() > 3 && ev.get(3) instanceof Number n)
                                ? n.floatValue() : 1.0f;
                        String sound = (instIdx >= 0 && instIdx < instruments.size()
                                && instruments.get(instIdx) instanceof String s)
                                ? s : "block.note_block.harp";
                        // MIDI 音符号 → 音符盒音高：0.5 * 2^((midi-54)/12)
                        double pitch = 0.5d * Math.pow(2.0d, (midi - 54.0d) / 12.0d);
                        note(p.getLocation(), sound, vol, pitch);
                        index[0]++;
                    }
                    tick[0]++;
                    if (tick[0] >= songLength) {
                        tick[0] = 0;
                        index[0] = 0;
                    }
                } catch (Throwable ignored) {
                    // 单个音符失败不影响整首曲子
                }
            }, 0L, 1L);
            state.phaseTwo.musicTask = task;
        } catch (Throwable t) {
            BossLog.warn("二阶段 BGM 启动失败: " + t);
        }
    }

    /** 内置 Fever Pitch 循环（JSON 缺失时的退路；照抄原脚本 fallbackTask）。 */
    private static void startFeverFallback(BossState state, Player player) {
        try {
            int[] index = {0};
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(TT.plugin, () -> {
                try {
                    // ★ 每拍重取在线玩家（重连后任务不再永久失效；原脚本注释同款口径）：
                    //   用「玩家在线 + 战局还在」而不是「盔甲架还在吗」——
                    //   三阶段本体是僵尸，盔甲架早就没了
                    Player p = online(state);
                    if (p == null || BossFight.byKey(state.key) != state) {
                        BossState.cancelTask(state.phaseTwo.musicTask);
                        state.phaseTwo.musicTask = null;
                        return;
                    }
                    int i = index[0] % FEVER_PITCH_MELODY.length;
                    Location loc = p.getLocation();
                    note(loc, "block.note_block.bit", 1.0f, FEVER_PITCH_MELODY[i]);
                    note(loc, "block.note_block.bass", 0.95f, FEVER_PITCH_BASS[i]);
                    note(loc, "block.note_block.hat", 0.5f, 1.4f);
                    if (i % 4 == 0) note(loc, "block.note_block.snare", 0.7f, 1.0f);
                    if (i % 8 == 0) note(loc, "block.note_block.basedrum", 0.9f, 0.8f);
                    index[0]++;
                } catch (Throwable ignored) {
                    // 忽略
                }
            }, 0L, PHASE_TWO_PERIOD);
            state.phaseTwo.musicTask = task;
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 换战斗曲（原脚本 {@code switchPhaseTwoMusic}）：新曲读不出就保持原曲，已经是这首就不打断。 */
    public static boolean switchPhaseTwo(BossState state, Player player, String songFile) {
        try {
            if (state == null || player == null || !player.isOnline()) return false;
            if (songFile != null && songFile.equals(state.phaseTwo.musicFile)
                    && state.phaseTwo.musicTask != null) {
                return true;    // 已经是这首：不打断重播（原脚本同款）
            }
            if (loadSong(songFile) == null) {
                BossLog.warn("战斗曲读取失败，保持当前曲目: " + songFile);
                return false;
            }
            stopPhaseTwo(state);
            state.phaseTwo.musicFile = songFile;
            startPhaseTwo(state, player.getLocation(), songFile);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 停二阶段 BGM：取消任务 + 按名停掉七个音效（只停本局玩家那个）。 */
    public static void stopPhaseTwo(BossState state) {
        try {
            if (state == null) return;
            BossState.cancelTask(state.phaseTwo.musicTask);
            state.phaseTwo.musicTask = null;
            Player player = (state.playerName == null)
                    ? null : Bukkit.getPlayerExact(state.playerName);
            if (player == null) return;
            for (String sound : PHASE_TWO_SOUNDS) {
                try {
                    player.stopSound(sound);
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /**
     * 读曲目 JSON（原脚本 {@code loadBossSongData}）。
     *
     * <p>standalone 下曲目文件放在本插件数据目录里；兼容 RSC 的
     * {@code addons/糖然科技 TangranTech/scripts/道法/} 相对路径（RSC 同机共存时可直接复用旧文件）。</p>
     */
    private static Map<String, Object> loadSong(String fileName) {
        try {
            String name = (fileName == null || fileName.isEmpty()) ? "FEVER_PITCH.json" : fileName;
            java.io.File base = TT.plugin.getDataFolder();
            String[] candidates = {
                    new java.io.File(new java.io.File(
                            new java.io.File(base, "addons"), "糖然科技 TangranTech")
                            .toPath().toString(), "scripts/道法/" + name).toString(),
                    new java.io.File(base, "道法/" + name).toString(),
                    new java.io.File(base, name).toString(),
            };
            for (String path : candidates) {
                java.io.File f = new java.io.File(path);
                if (!f.exists()) continue;
                String text = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                return com.tangran.tangrantech.util.Json.parseObject(text);
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /** 本局召唤者的在线实例（离线返回 null；每拍重取，不持有旧引用）。 */
    private static Player online(BossState state) {
        try {
            if (state == null || state.playerName == null) return null;
            Player p = Bukkit.getPlayerExact(state.playerName);
            return (p != null && p.isOnline()) ? p : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 供卸载/调试使用。 */
    public static void shutdown() {
        // 无全局状态（任务句柄都存在各局 state 上）
    }
}
