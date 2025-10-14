package me.akram.bensalem.papperconverter.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.runBlocking
import me.akram.bensalem.papperconverter.service.PdfOcrService
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

class PdfOcrConfigurable : Configurable {
    private var panel: JPanel? = null
    private lateinit var apiKeyField: JPasswordField
    private lateinit var includeImages: JCheckBox
    private lateinit var combinePages: JCheckBox
    private lateinit var openAfter: JCheckBox
    private lateinit var outputMarkdown: JCheckBox
    private lateinit var outputJson: JCheckBox
    private lateinit var overwritePolicyCombo: JComboBox<String>
    private lateinit var testButton: JButton
    private lateinit var offlineRadio: JRadioButton
    private lateinit var mistralRadio: JRadioButton
    private lateinit var markitdownCmdField: JTextField
    private lateinit var checkMarkitdownButton: JButton

    override fun getDisplayName(): String = "PDF to Markdown OCR"

    override fun createComponent(): JComponent {
        if (panel == null) {
            apiKeyField = JPasswordField(30)
            testButton = JButton("Test Connection")
            includeImages = JCheckBox("Include images")
            combinePages = JCheckBox("Combine pages into one file")
            openAfter = JCheckBox("Open generated files")
            outputMarkdown = JCheckBox("Output Markdown")
            outputJson = JCheckBox("Output JSON")
            overwritePolicyCombo = ComboBox(PdfOcrSettingsState.OverwritePolicy.options)
            offlineRadio = JRadioButton("Offline")
            mistralRadio = JRadioButton("Mistral AI")
            val group = ButtonGroup().apply {
                add(offlineRadio)
                add(mistralRadio)
            }

            markitdownCmdField = JTextField(30)
            checkMarkitdownButton = JButton("Check MarkItDown")

            val modeInfo = JBLabel("<html>Offline mode uses the MarkItDown CLI to extract content locally.<br/>Provide the command path below or ensure it is on PATH. You can verify using the Check button. Example install: <code>pip install markitdown[all]</code>.</html>")

            val leftColumn = JPanel(GridBagLayout()).apply {
                val gbc = GridBagConstraints()

                // Mode label
                gbc.gridx = 0
                gbc.gridy = 0
                gbc.anchor = GridBagConstraints.WEST
                gbc.insets = JBUI.insetsRight(10)
                add(JBLabel("Mode:"), gbc)

                // Mode radios panel
                val modePanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(offlineRadio)
                    add(mistralRadio)
                    add(Box.createVerticalStrut(4))
                    add(modeInfo)
                }
                gbc.gridx = 1
                gbc.gridy = 0
                gbc.weightx = 1.0
                gbc.fill = GridBagConstraints.HORIZONTAL
                gbc.insets = JBUI.insetsBottom(8)
                add(modePanel, gbc)

                // API Key label
                gbc.gridx = 0
                gbc.gridy = 1
                gbc.weightx = 0.0
                gbc.fill = GridBagConstraints.NONE
                gbc.anchor = GridBagConstraints.WEST
                gbc.insets = JBUI.insetsRight(10)
                add(JBLabel("API Key:"), gbc)

                // API Key field
                gbc.gridx = 1
                gbc.gridy = 1
                gbc.weightx = 1.0
                gbc.fill = GridBagConstraints.HORIZONTAL
                gbc.insets = JBUI.insetsBottom(8)
                add(apiKeyField, gbc)

                // Test button
                gbc.gridx = 1
                gbc.gridy = 2
                gbc.weightx = 0.0
                gbc.fill = GridBagConstraints.NONE
                gbc.anchor = GridBagConstraints.WEST
                gbc.insets = JBUI.emptyInsets()
                add(testButton, gbc)
            }

            val rightColumn = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(includeImages)
                add(Box.createVerticalStrut(8))
                add(combinePages)
                add(Box.createVerticalStrut(8))
                add(openAfter)
            }

            val topRow = JPanel(GridBagLayout()).apply {
                val gbc = GridBagConstraints()

                gbc.gridx = 0
                gbc.gridy = 0
                gbc.weightx = 1.0
                gbc.fill = GridBagConstraints.HORIZONTAL
                gbc.anchor = GridBagConstraints.NORTHWEST
                gbc.insets = JBUI.insetsRight(30)
                add(leftColumn, gbc)

                gbc.gridx = 1
                gbc.weightx = 0.0
                gbc.fill = GridBagConstraints.NONE
                gbc.insets = JBUI.emptyInsets()
                add(rightColumn, gbc)
            }

            fun updateApiKeyControlsEnabled() {
                val mistral = mistralRadio.isSelected
                apiKeyField.isEnabled = mistral
                testButton.isEnabled = mistral

                val offline = offlineRadio.isSelected
                markitdownCmdField.isEnabled = offline
                checkMarkitdownButton.isEnabled = offline
            }

            offlineRadio.addActionListener { updateApiKeyControlsEnabled() }
            mistralRadio.addActionListener { updateApiKeyControlsEnabled() }

            testButton.addActionListener {
                val s = PdfOcrSettingsState.getInstance()
                val typed = String(apiKeyField.password)
                val key = if (typed == "********") s.apiKey else typed
                if (key.isBlank()) {
                    Messages.showErrorDialog("Please enter API key before testing.", "PDF OCR")
                } else {
                    val project = ProjectManager.getInstance().openProjects.firstOrNull()
                    if (project != null) {
                        runBlocking {
                            val result = PdfOcrService.getInstance(project).testConnection(key)
                            if (!result.ok) {
                                Messages.showErrorDialog(result.message, "PDF OCR")
                            } else {
                                Messages.showInfoMessage(result.message, "PDF OCR")
                            }
                        }
                    }
                }
            }

            checkMarkitdownButton.addActionListener {
                val cmd = markitdownCmdField.text.trim()
                if (cmd.isEmpty()) {
                    Messages.showErrorDialog("Please enter the MarkItDown command or full path.", "PDF OCR")
                } else {
                    val project = ProjectManager.getInstance().openProjects.firstOrNull()
                    if (project != null) {
                        val result = PdfOcrService.getInstance(project).checkMarkItDown(cmd)
                        if (!result.ok) {
                            Messages.showErrorDialog(result.message, "PDF OCR")
                        } else {
                            Messages.showInfoMessage(result.message, "PDF OCR")
                        }
                    } else {
                        try {
                            val proc = ProcessBuilder(cmd, "--version").redirectErrorStream(true).start()
                            val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
                            val exit = proc.waitFor()
                            if (exit == 0) {
                                Messages.showInfoMessage(if (out.isNotBlank()) out else "MarkItDown detected", "PDF OCR")
                            } else {
                                Messages.showErrorDialog(out.ifBlank { "Failed to run MarkItDown" }, "PDF OCR")
                            }
                        } catch (e: Exception) {
                            Messages.showErrorDialog("Failed to run MarkItDown: ${e.message}", "PDF OCR")
                        }
                    }
                }
            }

            val outputFormatPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(outputMarkdown)
                add(Box.createVerticalStrut(4))
                add(outputJson)
            }

            val markitdownPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(markitdownCmdField)
                add(Box.createHorizontalStrut(8))
                add(checkMarkitdownButton)
            }

            panel = FormBuilder.createFormBuilder()
                .addComponent(topRow)
                .addVerticalGap(10)
                .addLabeledComponent(JBLabel("MarkItDown command:"), markitdownPanel)
                .addVerticalGap(15)
                .addLabeledComponent(JBLabel("Overwrite Policy:"), overwritePolicyCombo)
                .addVerticalGap(10)
                .addLabeledComponent(JBLabel("Output Format:"), outputFormatPanel)
                .addComponentFillVertically(JPanel(), 0)
                .panel.apply {
                    border = JBUI.Borders.empty(10)
                }
        }
        reset()
        return panel as JPanel
    }

    override fun isModified(): Boolean {
        val s = PdfOcrSettingsState.getInstance()
        val typed = String(apiKeyField.password)
        val keyChanged = when {
            typed == "********" -> false
            typed.isEmpty() -> s.hasApiKey()
            else -> true
        }
        val modeChanged = when (s.state.mode) {
            PdfOcrSettingsState.OcrMode.Offline -> !offlineRadio.isSelected
            PdfOcrSettingsState.OcrMode.Mistral -> !mistralRadio.isSelected
        }
        return keyChanged || modeChanged ||
                includeImages.isSelected != s.state.includeImages ||
                combinePages.isSelected != s.state.combinePages ||
                openAfter.isSelected != s.state.openAfterConvert ||
                outputMarkdown.isSelected != s.state.outputMarkdown ||
                outputJson.isSelected != s.state.outputJson ||
                overwritePolicyCombo.selectedIndex != s.state.overwritePolicy.ordinal ||
                markitdownCmdField.text.trim() != s.state.markitdownCmd
    }

    override fun apply() {
        val s = PdfOcrSettingsState.getInstance()
        val typed = String(apiKeyField.password)
        when {
            typed == "********" -> { /* no change to stored key */ }
            typed.isNotEmpty() -> {
                s.apiKey = typed
            }
            else -> {
                s.apiKey = ""
            }
        }

        // Reflect masked or empty state in the UI field after applying
        apiKeyField.text = if (s.hasApiKey()) "********" else ""

        val newMode = if (mistralRadio.isSelected) PdfOcrSettingsState.OcrMode.Mistral else PdfOcrSettingsState.OcrMode.Offline

        s.loadState(
            s.state.copy(
                includeImages = includeImages.isSelected,
                combinePages = combinePages.isSelected,
                openAfterConvert = openAfter.isSelected,
                outputMarkdown = outputMarkdown.isSelected,
                outputJson = outputJson.isSelected,
                overwritePolicy = PdfOcrSettingsState.OverwritePolicy.entries[overwritePolicyCombo.selectedIndex],
                mode = newMode,
                markitdownCmd = markitdownCmdField.text.trim(),
            )
        )
    }

    override fun reset() {
        val s = PdfOcrSettingsState.getInstance()
        apiKeyField.text = if (s.hasApiKey()) "********" else ""
        includeImages.isSelected = s.state.includeImages
        combinePages.isSelected = s.state.combinePages
        openAfter.isSelected = s.state.openAfterConvert
        outputMarkdown.isSelected = s.state.outputMarkdown
        outputJson.isSelected = s.state.outputJson
        overwritePolicyCombo.selectedIndex = s.state.overwritePolicy.ordinal

        // Mode selection
        when (s.state.mode) {
            PdfOcrSettingsState.OcrMode.Offline -> offlineRadio.isSelected = true
            PdfOcrSettingsState.OcrMode.Mistral -> mistralRadio.isSelected = true
        }
        // Enable/disable API key controls accordingly
        apiKeyField.isEnabled = s.state.mode == PdfOcrSettingsState.OcrMode.Mistral
        testButton.isEnabled = s.state.mode == PdfOcrSettingsState.OcrMode.Mistral

        // MarkItDown command
        markitdownCmdField.text = s.state.markitdownCmd
        val offline = s.state.mode == PdfOcrSettingsState.OcrMode.Offline
        markitdownCmdField.isEnabled = offline
        checkMarkitdownButton.isEnabled = offline
    }

    override fun disposeUIResources() {
        panel = null
    }
}