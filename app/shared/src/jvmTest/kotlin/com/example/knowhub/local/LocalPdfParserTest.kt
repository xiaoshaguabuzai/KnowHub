package com.example.knowhub.local

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

class LocalPdfParserTest {

    @Test
    fun extractsTextFromPdfAttachment() {
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)
        PDPageContentStream(document, page).use { stream ->
            stream.beginText()
            stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
            stream.newLineAtOffset(50f, 700f)
            stream.showText("Potassium 6.8 mmol/L, recheck advised.")
            stream.endText()
        }
        val output = ByteArrayOutputStream()
        document.save(output)
        document.close()

        val text = parseLocalAttachmentText("lab-report.pdf", "application/pdf", output.toByteArray())
        assertTrue(text.contains("Potassium"), "PDF 文本应被抽取：$text")
        assertTrue(text.contains("6.8"))
    }
}
