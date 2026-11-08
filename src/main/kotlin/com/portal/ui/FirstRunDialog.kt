package com.portal.ui

import com.portal.core.Locations
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.UIManager
import kotlin.system.exitProcess

/** При самом первом запуске спрашивает, куда сохранять файлы (до любой установки). */
object FirstRunDialog {
    fun askIfNeeded() {
        if (!Locations.isFirstRun()) return
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } catch (_: Exception) {}
        val options = arrayOf("Выбрать папку…", "Стандартная папка")
        while (true) {
            val choice = JOptionPane.showOptionDialog(
                null,
                "Куда сохранять файлы Portal Launcher\n(версии Minecraft, библиотеки, миры, моды)?\n\n" +
                    "Стандартная папка:\n${Locations.defaultRoot}\n\n" +
                    "Можно выбрать другой диск, где больше места.\nЛучше использовать путь без русских букв.",
                "Portal Launcher — папка данных",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0],
            )
            when (choice) {
                0 -> {
                    val fc = JFileChooser()
                    fc.dialogTitle = "Выберите папку для данных Portal"
                    fc.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        val dir = fc.selectedFile.absolutePath
                        if (dir.any { it.code > 127 }) {
                            val ok = JOptionPane.showConfirmDialog(
                                null, "В пути есть не латинские символы:\n$dir\n\nУ старых версий Minecraft с ними бывают проблемы. Всё равно использовать?",
                                "Предупреждение", JOptionPane.YES_NO_OPTION
                            )
                            if (ok != JOptionPane.YES_OPTION) continue
                        }
                        try {
                            Locations.setCustomRoot(dir); return
                        } catch (e: Exception) {
                            JOptionPane.showMessageDialog(null, "Не удалось использовать эту папку: ${e.message}")
                        }
                    }
                }
                1 -> return
                else -> exitProcess(0)
            }
        }
    }
}
