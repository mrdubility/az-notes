package com.az.notes.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.az.notes.R
import com.az.notes.domain.model.NoteSortOrder

/** 排序方式展示名（主页顶栏排序菜单与设置页共用，避免两处定义漂移）。 */
@Composable
internal fun NoteSortOrder.label(): String = stringResource(
    when (this) {
        NoteSortOrder.MODIFIED_DESC -> R.string.sort_modified_desc
        NoteSortOrder.MODIFIED_ASC -> R.string.sort_modified_asc
        NoteSortOrder.NAME_ASC -> R.string.sort_name_asc
        NoteSortOrder.NAME_DESC -> R.string.sort_name_desc
    }
)
