package com.example.igguard

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

class InstagramGuardService : AccessibilityService() {

    private val TAG = "IGGuard"
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null

    // ===================== TODO: подставь свои реальные ID =====================
    // Найди их через Android Studio -> Layout Inspector (пока приложение открыто на
    // нужном экране) или `adb shell uiautomator dump` + просмотр XML.

    private val REELS_TAB_ID_CANDIDATES = listOf(
        "com.instagram.android:id/reels_tab",
        "com.instagram.android:id/clips_tab"
    )

    private val REELS_PLAYER_ID_CANDIDATES = listOf(
        "com.instagram.android:id/clips_viewer_view_pager",
        "com.instagram.android:id/clips_viewer_container",
        "com.instagram.android:id/root_clips_layout"
    )

    private val REELS_AUTHOR_ID_CANDIDATES = listOf(
        "com.instagram.android:id/clips_author_username"
    )

    // Экран переписки (тред) Direct — по нему понимаем, что ролик открыт из личных сообщений.
    // Подтверждено дампом реального экрана.
    private val DIRECT_THREAD_ID_CANDIDATES = listOf(
        "com.instagram.android:id/thread_view_root",
        "com.instagram.android:id/message_list",
        "com.instagram.android:id/direct_thread_header"
    )
    // ============================================================================

    // ===================== Позиционный фолбэк для вкладки Reels =====================
    private val REELS_TAB_POSITION_INDEX = 2
    private val MIN_TAB_BAR_CHILDREN = 3
    private val MAX_TAB_BAR_CHILDREN = 6
    private val TAB_BAR_MIN_TOP_FRACTION = 0.85
    private val TAB_BAR_MIN_WIDTH_FRACTION = 0.8
    // ==================================================================================

    private enum class Origin { DIRECT_MESSAGE, OTHER, UNKNOWN }

    // Состояние текущего "захода" в полноэкранный плеер Reels
    private var reelPlayerActive = false
    private var currentOrigin: Origin = Origin.UNKNOWN
    private var sessionStartTime = 0L
    private var lastHandledOpenSignature: String? = null

    // Запоминаем, был ли самый последний НЕ-плеерный экран похож на тред Direct
    private var lastNonPlayerScreenWasDirectThread = false

    private val SWIPE_GRACE_PERIOD_MS = 900L // не считаем скроллом "устаканивание" первого ролика

    // После срабатывания блокировки (свайп или неподходящий автор) игнорируем ВСЁ
    // происходящее в плеере это время — один жест смахивания генерирует несколько
    // событий TYPE_VIEW_SCROLLED подряд, и без такого "охлаждения" можно несколько
    // раз подряд нажать "назад" за доли секунды и вылететь из приложения целиком.
    private var ignoreUntilTimestamp = 0L
    private val COOLDOWN_AFTER_ACTION_MS = 1200L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val root = rootInActiveWindow ?: return
        val now = System.currentTimeMillis()

        // 1) Блокировка нажатия на вкладку Reels снизу
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val source = event.source
            if (source != null) {
                val byId = idMatches(source, REELS_TAB_ID_CANDIDATES)
                val byPosition = !byId && isReelsTabByPosition(source)
                if (byId || byPosition) {
                    Log.d(TAG, "Клик по вкладке Reels перехвачен (${if (byId) "по id" else "позиционно"}), блокирую")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    return
                }
            }
        }

        // Период охлаждения после недавнего срабатывания — полностью игнорируем
        // всё, что происходит в плеере, пока дребезг от предыдущего действия не уляжется.
        if (now < ignoreUntilTimestamp) {
            return
        }

        val playerNode = REELS_PLAYER_ID_CANDIDATES
            .mapNotNull { root.findAccessibilityNodeInfosByViewId(it) }
            .flatten()
            .firstOrNull { isFullScreenNode(it) }

        if (playerNode == null) {
            // Плеер закрыт — сбрасываем состояние сессии и запоминаем, похож ли
            // текущий (не-плеерный) экран на тред Direct — это понадобится, когда
            // плеер откроется в следующий раз.
            if (reelPlayerActive) {
                Log.d(TAG, "Плеер Reels закрыт, сброс состояния сессии")
            }
            reelPlayerActive = false
            currentOrigin = Origin.UNKNOWN
            removeOverlay()

            lastNonPlayerScreenWasDirectThread = isDirectThreadScreen(root)
            return
        }

        // Плеер открыт
        if (!reelPlayerActive) {
            // Новая сессия — определяем происхождение по тому, что было ДО открытия плеера
            reelPlayerActive = true
            sessionStartTime = now
            currentOrigin = if (lastNonPlayerScreenWasDirectThread) Origin.DIRECT_MESSAGE else Origin.OTHER
            lastHandledOpenSignature = null

            Log.d(TAG, "Открыт плеер Reels, источник = $currentOrigin")
            handleReelPlayerSessionStart()
            return
        }

        // Плеер уже был открыт и остаётся открытым — проверяем попытку пролистать
        if (currentOrigin == Origin.DIRECT_MESSAGE &&
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            val elapsed = now - sessionStartTime
            if (elapsed > SWIPE_GRACE_PERIOD_MS) {
                Log.d(TAG, "Попытка пролистать дальше ролика из DM — блокирую и выхожу")
                showOverlay()
                performGlobalAction(GLOBAL_ACTION_BACK)
                handler.postDelayed({ removeOverlay() }, 150)
                reelPlayerActive = false
                currentOrigin = Origin.UNKNOWN
                ignoreUntilTimestamp = now + COOLDOWN_AFTER_ACTION_MS
            }
        }
    }

    /**
     * Начало новой сессии просмотра плеера Reels.
     * - Если источник — Direct (личка): разрешаем посмотреть именно этот ролик,
     *   дальше блокируем любые попытки пролистать (см. onAccessibilityEvent).
     * - Если источник — не Direct: проверяем автора по старому фолбэку
     *   "разрешено, если это ролик друга" (например, открыт с профиля друга).
     */
    private fun handleReelPlayerSessionStart() {
        if (currentOrigin == Origin.DIRECT_MESSAGE) {
            // Ролик прислан в личку — сразу показываем, лист��ть дальше не дадим (см. выше).
            return
        }

        // Источник не Direct — работает прежняя проверка по автору как доп. критерий.
        showOverlay()

        var attempts = 0
        val maxAttempts = 6
        val checkInterval = 60L

        val checker = object : Runnable {
            override fun run() {
                val root = rootInActiveWindow
                val author = root?.let { findAuthorUsername(it) }

                if (author != null) {
                    if (FriendsStore.isFriend(this@InstagramGuardService, author)) {
                        Log.d(TAG, "Автор '$author' в списке друзей — показываю")
                        removeOverlay()
                    } else {
                        Log.d(TAG, "Автор '$author' НЕ в списке друзей, источник не DM — блокирую")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        handler.postDelayed({ removeOverlay() }, 150)
                        reelPlayerActive = false
                        currentOrigin = Origin.UNKNOWN
                        ignoreUntilTimestamp = System.currentTimeMillis() + COOLDOWN_AFTER_ACTION_MS
                    }
                    return
                }

                attempts++
                if (attempts < maxAttempts) {
                    handler.postDelayed(this, checkInterval)
                } else {
                    Log.d(TAG, "Не удалось распознать автора — блокирую по умолчанию")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    handler.postDelayed({ removeOverlay() }, 150)
                    reelPlayerActive = false
                    currentOrigin = Origin.UNKNOWN
                    ignoreUntilTimestamp = System.currentTimeMillis() + COOLDOWN_AFTER_ACTION_MS
                }
            }
        }
        handler.post(checker)
    }

    /**
     * Похож ли текущий экран на тред переписки Direct.
     * Сначала пробуем по resource-id, затем — грубый фолбэк.
     */
    private fun isDirectThreadScreen(root: AccessibilityNodeInfo): Boolean {
        val byId = DIRECT_THREAD_ID_CANDIDATES.any { id ->
            !root.findAccessibilityNodeInfosByViewId(id).isNullOrEmpty()
        }
        if (byId) return true

        // Фолбэк: ищем строку ввода сообщения (обычно есть только в Direct/чатах)
        return hasMessageComposerHint(root)
    }

    private fun hasMessageComposerHint(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 10) return false
        val desc = node.contentDescription?.toString()?.lowercase()
        val hint = node.text?.toString()?.lowercase()
        if (desc?.contains("message") == true || hint?.contains("message") == true) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasMessageComposerHint(child, depth + 1)) return true
        }
        return false
    }

    private fun findAuthorUsername(root: AccessibilityNodeInfo): String? {
        for (id in REELS_AUTHOR_ID_CANDIDATES) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            val text = nodes?.firstOrNull()?.text?.toString()
            if (!text.isNullOrBlank()) return text
        }
        return searchUsernameFallback(root)
    }

    private fun searchUsernameFallback(node: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth > 12) return null
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && text.matches(Regex("^[a-zA-Z0-9._]{2,30}$"))) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.top < resources.displayMetrics.heightPixels / 3) {
                return text
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchUsernameFallback(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun idMatches(node: AccessibilityNodeInfo, ids: List<String>): Boolean {
        return node.viewIdResourceName != null && node.viewIdResourceName in ids
    }

    /**
     * Проверяет, что нода занимает почти весь экран — то есть это реально
     * полноэкранный плеер Reels, а не маленький встроенный ролик-превью в
     * основной ленте (Instagram переиспользует те же resource-id для обоих).
     */
    private fun isFullScreenNode(node: AccessibilityNodeInfo): Boolean {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val wideEnough = bounds.width() >= screenWidth * 0.85
        val tallEnough = bounds.height() >= screenHeight * 0.65

        if (!wideEnough || !tallEnough) {
            Log.d(TAG, "Найден узел плеера, но он мелкий (вероятно, встроенный ролик в ленте) — игнорирую")
        }
        return wideEnough && tallEnough
    }

    private fun isReelsTabByPosition(source: AccessibilityNodeInfo): Boolean {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        var node: AccessibilityNodeInfo? = source
        var depth = 0

        while (node != null && depth < 6) {
            val parent = node.parent
            if (parent == null) {
                depth++
                node = null
                continue
            }

            val childCount = parent.childCount
            if (childCount in MIN_TAB_BAR_CHILDREN..MAX_TAB_BAR_CHILDREN) {
                val parentBounds = Rect()
                parent.getBoundsInScreen(parentBounds)

                val looksLikeTabBar =
                    parentBounds.top > screenHeight * TAB_BAR_MIN_TOP_FRACTION &&
                        parentBounds.width() > screenWidth * TAB_BAR_MIN_WIDTH_FRACTION

                if (looksLikeTabBar) {
                    val nodeBounds = Rect()
                    node.getBoundsInScreen(nodeBounds)

                    val children = (0 until childCount).mapNotNull { parent.getChild(it) }
                    val sortedByX = children.sortedBy { c ->
                        val b = Rect()
                        c.getBoundsInScreen(b)
                        b.left
                    }

                    val clickedIndex = sortedByX.indexOfFirst { c ->
                        val b = Rect()
                        c.getBoundsInScreen(b)
                        b == nodeBounds
                    }

                    Log.d(TAG, "Позиционный индекс клика в таб-баре: $clickedIndex из $childCount")
                    return clickedIndex == REELS_TAB_POSITION_INDEX
                }
            }

            node = parent
            depth++
        }
        return false
    }

    private fun showOverlay() {
        if (overlayView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val view = View(this).apply { setBackgroundColor(Color.BLACK) }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.OPAQUE
        ).apply { gravity = Gravity.TOP or Gravity.START }

        windowManager?.addView(view, params)
        overlayView = view
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "removeOverlay: ${e.message}")
            }
        }
        overlayView = null
    }

    override fun onInterrupt() {}
}
