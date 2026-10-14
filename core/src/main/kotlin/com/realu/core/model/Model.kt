package com.realu.core.model

import com.realu.core.feature.FeatureDefs
import com.realu.core.strategy.ExitRule
import com.realu.core.strategy.GateParams
import com.realu.core.strategy.LabelParams
import com.realu.core.util.Json
import com.realu.core.util.asDouble
import com.realu.core.util.asJsonList
import com.realu.core.util.asJsonMap
import com.realu.core.util.dbl
import com.realu.core.util.int
import com.realu.core.util.list
import com.realu.core.util.obj
import com.realu.core.util.str
import com.realu.core.util.toDoubleArray
import com.realu.core.util.toIntArray
import kotlin.math.exp

/**
 * 一棵回归树,数组形式存储(便于序列化,推理时无对象分配)。
 * feature[i] < 0 表示叶子;非叶子:x <= threshold 走 left,否则 right;x 为 NaN 时 missingLeft 决定方向。
 * value 对所有节点都有值(内部节点为该节点样本的期望输出),用于计算各特征的贡献度(可解释性)。
 */
class Tree(
    val feature: IntArray,
    val threshold: DoubleArray,
    val missingLeft: BooleanArray,
    val left: IntArray,
    val right: IntArray,
    val value: DoubleArray,
) {
    fun leaf(x: FloatArray): Int {
        var node = 0
        while (feature[node] >= 0) {
            val v = x[feature[node]]
            node = if (v.isNaN()) {
                if (missingLeft[node]) left[node] else right[node]
            } else if (v <= threshold[node]) left[node] else right[node]
        }
        return node
    }

    fun predict(x: FloatArray): Double = value[leaf(x)]

    /** Saabas 路径归因:沿路径每次分裂的输出变化记在分裂特征头上。 */
    fun contribute(x: FloatArray, out: DoubleArray) {
        var node = 0
        while (feature[node] >= 0) {
            val f = feature[node]
            val v = x[f]
            val next = if (v.isNaN()) {
                if (missingLeft[node]) left[node] else right[node]
            } else if (v <= threshold[node]) left[node] else right[node]
            out[f] += value[next] - value[node]
            node = next
        }
    }

    fun toJson(): Map<String, Any?> = linkedMapOf(
        "f" to feature, "t" to threshold.map { if (it.isFinite()) it.toFloat().toDouble() else 0.0 },
        "m" to missingLeft.map { if (it) 1 else 0 }, "l" to left, "r" to right,
        "v" to value.map { it.toFloat().toDouble() },
    )

    companion object {
        fun fromJson(m: Map<String, Any?>): Tree {
            val f = m.list("f").toIntArray()
            val t = m.list("t").toDoubleArray()
            val mm = m.list("m").toIntArray()
            val l = m.list("l").toIntArray()
            val r = m.list("r").toIntArray()
            val v = m.list("v").toDoubleArray()
            require(listOf(t.size, mm.size, l.size, r.size, v.size).all { it == f.size }) { "树节点数组长度不一致" }
            for (i in f.indices) if (f[i] >= 0) {
                require(f[i] < FeatureDefs.COUNT && l[i] in f.indices && r[i] in f.indices && l[i] > i && r[i] > i) { "树结构损坏(节点 $i)" }
            }
            return Tree(f, t, BooleanArray(mm.size) { mm[it] != 0 }, l, r, v)
        }
    }
}

class GbdtModel(val baseScore: Double, val trees: List<Tree>) {
    fun rawScore(x: FloatArray): Double {
        var s = baseScore
        for (t in trees) s += t.predict(x)
        return s
    }

    fun probability(x: FloatArray): Double = sigmoid(rawScore(x))

    /** 每个特征对 log-odds 的贡献;返回数组长度 = 特征数,另附基准值。 */
    fun contributions(x: FloatArray): Pair<Double, DoubleArray> {
        val out = DoubleArray(FeatureDefs.COUNT)
        var bias = baseScore
        for (t in trees) {
            bias += t.value[0]
            t.contribute(x, out)
        }
        return bias to out
    }

    companion object {
        fun sigmoid(z: Double): Double = 1.0 / (1.0 + exp(-z))
    }
}

/**
 * 导出给 APP 的完整模型文件(.json,UTF-8)。
 * 除了树模型本身,还携带候选条件、卖出规则、阈值和历史检验指标,APP 导入即得到一套可执行的策略。
 */
class RealuModel(
    val name: String,
    val strategyId: String,
    val createdAt: String,
    val featureVersion: Int,
    val featureNames: List<String>,
    val gate: GateParams,
    val label: LabelParams,
    val exit: ExitRule,
    val gbdt: GbdtModel,
    /** 发信号的概率阈值(验证集上选定)。 */
    val threshold: Double,
    /** 历史检验是否达到配置的目标(准确率、样本数等)。未达标的模型 APP 应醒目提示。 */
    val passed: Boolean,
    /** 训练/检验指标,原样展示。 */
    val metrics: Map<String, Any?>,
    /** 特征组权重(由特征重要性汇总,合计 100),对应 APP"评分因子"。 */
    val factorWeights: Map<String, Int>,
    val dataInfo: Map<String, Any?>,
) {
    /** APP 展示用 0–100 分:阈值映射到 60 分(与原型"入选线 ≥ 60"一致)。 */
    fun score(probability: Double): Int {
        val th = threshold.coerceIn(1e-6, 1 - 1e-6)
        val s = if (probability <= th) probability / th * 60 else 60 + (probability - th) / (1 - th) * 40
        return s.toInt().coerceIn(0, 100)
    }

    fun checkCompatible() {
        require(featureVersion == FeatureDefs.VERSION) {
            "模型特征版本 $featureVersion 与程序特征版本 ${FeatureDefs.VERSION} 不一致,请用新版训练器重新训练"
        }
        require(featureNames == FeatureDefs.NAMES) { "模型特征列表与程序不一致,请重新训练" }
    }

    fun toJson(): String = Json.write(
        linkedMapOf(
            "format" to FORMAT,
            "formatVersion" to FORMAT_VERSION,
            "name" to name,
            "strategyId" to strategyId,
            "createdAt" to createdAt,
            "passed" to passed,
            "threshold" to threshold,
            "scoreLine" to 60,
            "gate" to linkedMapOf(
                "minListedDays" to gate.minListedDays, "minAmount5" to gate.minAmount5,
                "minRet1" to gate.minRet1, "minVolRatio" to gate.minVolRatio,
                "maxPriorRise20" to gate.maxPriorRise20, "requireUpClose" to gate.requireUpClose,
                "excludeSt" to gate.excludeSt, "resumeCooldown" to gate.resumeCooldown,
            ),
            "label" to linkedMapOf("horizon" to label.horizon, "target" to label.target, "stop" to label.stop),
            "exit" to linkedMapOf(
                "stopLoss" to exit.stopLoss, "takeProfit" to exit.takeProfit,
                "trailingActivate" to exit.trailingActivate, "trailingDrawdown" to exit.trailingDrawdown,
                "maxHoldDays" to exit.maxHoldDays,
            ),
            "display" to linkedMapOf(
                "hardConditions" to gate.describe().map { linkedMapOf("label" to it.first, "value" to it.second) },
                "scoreFactors" to factorWeights.map { linkedMapOf("name" to it.key, "weight" to it.value) },
                "exitText" to exit.describe(),
            ),
            "metrics" to metrics,
            "data" to dataInfo,
            "features" to linkedMapOf("version" to featureVersion, "names" to featureNames),
            "model" to linkedMapOf(
                "type" to "gbdt-binary-logistic",
                "baseScore" to gbdt.baseScore,
                "trees" to gbdt.trees.map { it.toJson() },
            ),
        ),
    )

    companion object {
        const val FORMAT = "realu-model"
        const val FORMAT_VERSION = 1

        fun fromJson(text: String): RealuModel {
            val root = Json.parse(text.removePrefix("﻿")).asJsonMap()
            require(root["format"] == FORMAT) { "不是 realU 模型文件" }
            require(root.int("formatVersion") <= FORMAT_VERSION) { "模型文件版本过新,请升级 APP" }
            val g = root.obj("gate")
            val lb = root.obj("label")
            val ex = root.obj("exit")
            val feats = root.obj("features")
            val mdl = root.obj("model")
            val display = root.obj("display")
            @Suppress("UNCHECKED_CAST")
            return RealuModel(
                name = root.str("name"),
                strategyId = root.str("strategyId"),
                createdAt = root.str("createdAt"),
                featureVersion = feats.int("version"),
                featureNames = feats.list("names").map { it as String },
                gate = GateParams(
                    minListedDays = g.int("minListedDays"), minAmount5 = g.dbl("minAmount5"),
                    minRet1 = g.dbl("minRet1"), minVolRatio = g.dbl("minVolRatio"),
                    maxPriorRise20 = g.dbl("maxPriorRise20"), requireUpClose = g["requireUpClose"] as Boolean,
                    excludeSt = g["excludeSt"] as Boolean, resumeCooldown = g.int("resumeCooldown"),
                ),
                label = LabelParams(lb.int("horizon"), lb.dbl("target"), lb.dbl("stop")),
                exit = ExitRule(ex.dbl("stopLoss"), ex.dbl("takeProfit"), ex.dbl("trailingActivate"), ex.dbl("trailingDrawdown"), ex.int("maxHoldDays")),
                gbdt = GbdtModel(mdl["baseScore"].asDouble(), mdl.list("trees").map { Tree.fromJson(it.asJsonMap()) }),
                threshold = root.dbl("threshold"),
                passed = root["passed"] as? Boolean ?: false,
                metrics = root["metrics"] as? Map<String, Any?> ?: emptyMap(),
                factorWeights = display.list("scoreFactors").associate { val m = it.asJsonMap(); m.str("name") to m.int("weight") },
                dataInfo = root["data"] as? Map<String, Any?> ?: emptyMap(),
            )
        }
    }
}
