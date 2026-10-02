package com.phenix.xglobal.ctx.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * 自动初始化：ContentProvider 在 Application.onCreate 之前执行，
 * 三方无需修改任何代码即可完成初始化。
 *
 * 若三方合规/打包工具移除 Provider，可退回手动调用 AppGlobalContext.init(context)。
 */
internal class CtxAutoInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val context = context ?: return false
        AppGlobalContext.init(context)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
