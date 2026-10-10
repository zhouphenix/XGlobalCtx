package com.phenix.xglobal.ctx.android

import android.content.Context
import android.graphics.Bitmap
import androidx.palette.graphics.Palette
import com.phenix.xglobal.ctx.core.IGlobalCapability
import com.phenix.xglobal.ctx.core.IGlobalContext
import com.phenix.xglobal.ctx.core.StateKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 色板条目:颜色值 + 像素占比(population,越大表示该颜色在图中覆盖越多)。 */
public data class SwatchEntry(public val color: Int, public val population: Int)

/**
 * 内置能力:图片调色板(基于 androidx.palette)。
 *
 * 调用 [extract] 异步提取位图的主色、六种命名色调与全部色板;
 * 提取期间 [BusyKey] 为 true,完成后各状态键写入颜色值(0 表示无)。
 * 所有结果键均可通过 [IGlobalContext.store.flow] 订阅,用于换肤等联动。
 */
public class PaletteCapability(
    private val context: IGlobalContext,
    @Suppress("unused") private val appContext: Context,
) : IGlobalCapability {

    override val id: String = "xglobal.palette"

    public object BusyKey : StateKey<Boolean>("xglobal.palette.busy", false)
    public object DominantKey : StateKey<Int>("xglobal.palette.dominant", 0)
    public object VibrantKey : StateKey<Int>("xglobal.palette.vibrant", 0)
    public object LightVibrantKey : StateKey<Int>("xglobal.palette.light_vibrant", 0)
    public object DarkVibrantKey : StateKey<Int>("xglobal.palette.dark_vibrant", 0)
    public object MutedKey : StateKey<Int>("xglobal.palette.muted", 0)
    public object LightMutedKey : StateKey<Int>("xglobal.palette.light_muted", 0)
    public object DarkMutedKey : StateKey<Int>("xglobal.palette.dark_muted", 0)
    public object SwatchesKey : StateKey<List<SwatchEntry>>("xglobal.palette.swatches", emptyList())

    public val busy: Boolean get() = context.store.get(BusyKey)
    public val dominant: Int get() = context.store.get(DominantKey)
    public val vibrant: Int get() = context.store.get(VibrantKey)
    public val lightVibrant: Int get() = context.store.get(LightVibrantKey)
    public val darkVibrant: Int get() = context.store.get(DarkVibrantKey)
    public val muted: Int get() = context.store.get(MutedKey)
    public val lightMuted: Int get() = context.store.get(LightMutedKey)
    public val darkMuted: Int get() = context.store.get(DarkMutedKey)
    public val swatches: List<SwatchEntry> get() = context.store.get(SwatchesKey)
    public val busyFlow: StateFlow<Boolean> get() = context.store.flow(BusyKey)
    public val swatchesFlow: StateFlow<List<SwatchEntry>> get() = context.store.flow(SwatchesKey)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** 异步提取位图调色板;重复调用以最新一次为准(自动取消上一次)。 */
    public fun extract(bitmap: Bitmap) {
        job?.cancel()
        context.store.set(BusyKey, true)
        job = scope.launch {
            val palette = Palette.from(bitmap).generate()
            context.store.set(DominantKey, palette.dominantSwatch?.rgb ?: 0)
            context.store.set(VibrantKey, palette.vibrantSwatch?.rgb ?: 0)
            context.store.set(LightVibrantKey, palette.lightVibrantSwatch?.rgb ?: 0)
            context.store.set(DarkVibrantKey, palette.darkVibrantSwatch?.rgb ?: 0)
            context.store.set(MutedKey, palette.mutedSwatch?.rgb ?: 0)
            context.store.set(LightMutedKey, palette.lightMutedSwatch?.rgb ?: 0)
            context.store.set(DarkMutedKey, palette.darkMutedSwatch?.rgb ?: 0)
            context.store.set(
                SwatchesKey,
                palette.swatches
                    .sortedByDescending { it.population }
                    .map { SwatchEntry(it.rgb, it.population) },
            )
            context.store.set(BusyKey, false)
        }
    }

    override fun onDetach() {
        job?.cancel()
        scope.cancel()
    }
}
