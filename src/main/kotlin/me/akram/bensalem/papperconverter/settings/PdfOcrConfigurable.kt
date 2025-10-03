package me.akram.bensalem.papperconverter.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import javax.swing.*

class PdfOcrConfigurable : Configurable {
    private var panel: JPanel? = null
    private lateinit var apiKeyField: JPasswordField
    private lateinit var includeImages: JCheckBox
    private lateinit var combinePages: JCheckBox
    private lateinit var openAfter: JCheckBox
    private lateinit var outputModeCombo: JComboBox<PdfOcrSettingsState.OutputMode>
    private lateinit var overwritePolicyCombo: JComboBox<PdfOcrSettingsState.OverwritePolicy>
    private lateinit var projectOutputRootField: JTextField
    private lateinit var testButton: JButton

    override fun getDisplayName(): String = "PDF to Markdown OCR"

    override fun createComponent(): JComponent {
        if (panel == null) {
            val p = JPanel()
            p.layout = BoxLayout(p, BoxLayout.Y_AXIS)

            apiKeyField = JPasswordField(40)
            includeImages = JCheckBox("Include images", true)
            combinePages = JCheckBox("Combine pages into one Markdown file", true)
            openAfter = JCheckBox("Open Markdown after conversion", true)
            outputModeCombo = JComboBox(PdfOcrSettingsState.OutputMode.values())
            overwritePolicyCombo = JComboBox(PdfOcrSettingsState.OverwritePolicy.values())
            projectOutputRootField = JTextField(40)
            testButton = JButton("Test connection")

            p.add(labeled("API Key", apiKeyField))
            p.add(includeImages)
            p.add(combinePages)
            p.add(openAfter)
            p.add(labeled("Output Mode", outputModeCombo))
            p.add(labeled("Overwrite Policy", overwritePolicyCombo))
            p.add(labeled("Project Output Root (for ProjectOutputRoot mode)", projectOutputRootField))
            p.add(testButton)

            testButton.addActionListener {
                val key = String(apiKeyField.password)
                if (key.isBlank()) {
                    Messages.showErrorDialog("Please enter API key before testing.", "PDF OCR")
                } else {
                    // For now, just simulate success; the service will do real HTTP in project context
                    Messages.showInfoMessage("API key looks set. Connection test simulated.", "PDF OCR")
                }
            }

            panel = p
        }
        reset()
        return panel as JPanel
    }

    private fun labeled(label: String, comp: JComponent): JPanel {
        val p = JPanel()
        p.layout = BoxLayout(p, BoxLayout.X_AXIS)
        p.add(JLabel(label))
        p.add(Box.createHorizontalStrut(8))
        p.add(comp)
        return p
    }

    override fun isModified(): Boolean {
        val s = PdfOcrSettingsState.getInstance()
        val keyChanged = (String(apiKeyField.password).isNotEmpty() != s.hasApiKey()) ||
            (String(apiKeyField.password).isNotEmpty() && String(apiKeyField.password) != s.apiKey)
        return keyChanged ||
                includeImages.isSelected != s.state.includeImages ||
                combinePages.isSelected != s.state.combinePages ||
                openAfter.isSelected != s.state.openAfterConvert ||
                outputModeCombo.selectedItem != s.state.outputMode ||
                overwritePolicyCombo.selectedItem != s.state.overwritePolicy ||
                projectOutputRootField.text != (s.state.projectOutputRoot ?: "")
    }

    override fun apply() {
        val s = PdfOcrSettingsState.getInstance()
        val key = String(apiKeyField.password)
        if (key.isNotEmpty()) {
            s.apiKey = key
        } else if (!s.hasApiKey()) {
            s.apiKey = "" // clear if it was previously empty
        }
        s.loadState(
            s.state.copy(
                includeImages = includeImages.isSelected,
                combinePages = combinePages.isSelected,
                openAfterConvert = openAfter.isSelected,
                outputMode = outputModeCombo.selectedItem as PdfOcrSettingsState.OutputMode,
                overwritePolicy = overwritePolicyCombo.selectedItem as PdfOcrSettingsState.OverwritePolicy,
                projectOutputRoot = projectOutputRootField.text.ifBlank { null },
            )
        )
    }

    override fun reset() {
        val s = PdfOcrSettingsState.getInstance()
        apiKeyField.text = if (s.hasApiKey()) "********" else ""
        includeImages.isSelected = s.state.includeImages
        combinePages.isSelected = s.state.combinePages
        openAfter.isSelected = s.state.openAfterConvert
        outputModeCombo.selectedItem = s.state.outputMode
        overwritePolicyCombo.selectedItem = s.state.overwritePolicy
        projectOutputRootField.text = s.state.projectOutputRoot ?: ""
    }

    override fun disposeUIResources() {
        panel = null
    }
}
