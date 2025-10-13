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

            val modeInfo = JBLabel("<html>Offline mode uses Microsoft MarkItDown to extract content locally.</html>")

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

            val outputFormatPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(outputMarkdown)
                add(Box.createVerticalStrut(4))
                add(outputJson)
            }

            panel = FormBuilder.createFormBuilder()
                .addComponent(topRow)
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
                overwritePolicyCombo.selectedIndex != s.state.overwritePolicy.ordinal
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
    }

    override fun disposeUIResources() {
        panel = null
    }
}