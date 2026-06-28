package com.debugtools.audiomon.anomaly

/**
 * The kinds of audio anomalies the detector reports.
 * [hint] is the "what problem this may indicate" text reused by the collapsible
 * legend at the bottom of the panel.
 */
enum class AnomalyType(val label: String, val hint: String) {
    CLIPPING("削波", "输入增益过高或信号过强，波形被截顶产生谐波失真；常导致破音、ASR 识别率下降。"),
    SILENCE_DROPOUT("异常静音", "麦克风被占用/静音、采集中断或丢帧、VAD 误切；可能漏识别、对话中断。"),
    ENERGY_JUMP("能量突变", "突发噪声、回声、设备碰撞或 AGC 增益抖动；可能引起误唤醒、识别错误。"),
    HIGH_NOISE_FLOOR("底噪偏高", "环境噪声大、降噪/AEC 不足或硬件底噪高；降低信噪比，影响远场识别。")
}
