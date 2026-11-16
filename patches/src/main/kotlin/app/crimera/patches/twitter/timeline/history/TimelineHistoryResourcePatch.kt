/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.timeline.history

import app.crimera.patches.twitter.misc.settings.settingsResourcePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import org.w3c.dom.Element

internal val timelineHistoryResourcePatch =
    resourcePatch {
        dependsOn(settingsResourcePatch)

        execute {
            copyResources(
                "twitter/history",
                ResourceGroup("drawable", "ic_vector_history_shortcut.xml"),
            )

            // Add a compact history icon button inside the navigation drawer footer beside PikoSettingsButton.
            if (get("res/layout/main_activity_app_bar.xml", false).exists()) {
                document("res/layout/main_activity_app_bar.xml").use { editor ->
                    val frameLayouts = editor.getElementsByTagName("FrameLayout")
                    for (i in 0 until frameLayouts.length) {
                        val node = frameLayouts.item(i) as? Element ?: continue
                        if (node.getAttribute("android:id") == "@id/drawer") {
                            val button = editor.createElement("app.morphe.extension.twitter.settings.widgets.PikoHistoryButton")
                            button.setAttribute("android:layout_width", "48.0dp")
                            button.setAttribute("android:layout_height", "@dimen/docker_height")
                            button.setAttribute("android:layout_gravity", "bottom|center_horizontal")
                            button.setAttribute("android:layout_marginBottom", "2.3dp")
                            button.setAttribute("android:background", "?android:attr/selectableItemBackgroundBorderless")
                            button.setAttribute("android:src", "@drawable/ic_vector_history")
                            button.setAttribute("android:tint", "?attr/coreColorPrimaryText")
                            button.setAttribute("android:scaleType", "centerInside")
                            button.setAttribute("android:padding", "10.0dp")
                            button.setAttribute("android:contentDescription", "@string/piko_title_timeline_history")
                            node.appendChild(button)
                            break
                        }
                    }
                }
            }

            // Add launcher shortcut targeting https://x.com/i/piko/history (updated by ClonePatch if cloned).
            if (get("res/xml/shortcuts.xml", false).exists()) {
                document("res/xml/shortcuts.xml").use { editor ->
                    val shortcutsRoot = editor.getElementsByTagName("shortcuts").item(0) as? Element ?: return@use

                    val shortcut = editor.createElement("shortcut")
                    shortcut.setAttribute("android:enabled", "true")
                    shortcut.setAttribute("android:icon", "@drawable/ic_vector_history_shortcut")
                    shortcut.setAttribute("android:shortcutId", "timeline_history")
                    shortcut.setAttribute("android:shortcutLongLabel", "@string/piko_title_timeline_history")
                    shortcut.setAttribute("android:shortcutShortLabel", "@string/piko_title_timeline_history")

                    val intent = editor.createElement("intent")
                    intent.setAttribute("android:action", "android.intent.action.VIEW")
                    intent.setAttribute("android:data", "https://x.com/i/piko/history")
                    intent.setAttribute("android:targetClass", "com.twitter.deeplink.implementation.UrlInterpreterActivity")
                    intent.setAttribute("android:targetPackage", "com.twitter.android")

                    val extra = editor.createElement("extra")
                    extra.setAttribute("android:name", "shortcut")
                    extra.setAttribute("android:value", "timeline_history")

                    intent.appendChild(extra)
                    shortcut.appendChild(intent)

                    val shareTarget = editor.getElementsByTagName("share-target").item(0)
                    if (shareTarget != null) {
                        shortcutsRoot.insertBefore(shortcut, shareTarget)
                    } else {
                        shortcutsRoot.appendChild(shortcut)
                    }
                }
            }
        }
    }
