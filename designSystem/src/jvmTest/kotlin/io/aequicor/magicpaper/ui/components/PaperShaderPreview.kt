package io.aequicor.magicpaper.ui.components

import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode

/** Optional review artifact, generated from the production renderer with MAGICPAPER_PAPER_PREVIEW=1. */
internal fun writePaperPreview(render: (Float) -> ByteArray) {
    val writer = ImageIO.getImageWritersByFormatName("gif").next()
    val file = File("build/reports/paper-animation/paper-motion.gif")
    ImageIO.createImageOutputStream(file).use { output ->
        writer.output = output
        try {
            writer.prepareWriteSequence(null)
            for (frame in 0 until 120) {
                val image = ImageIO.read(render(frame / 10f).inputStream())
                val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null)
                val format = metadata.nativeMetadataFormatName
                val root = metadata.getAsTree(format) as IIOMetadataNode
                val control = root.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode
                control.setAttribute("disposalMethod", "none")
                control.setAttribute("delayTime", "10")
                control.setAttribute("transparentColorFlag", "FALSE")
                if (frame == 0) {
                    val extensions = IIOMetadataNode("ApplicationExtensions")
                    extensions.appendChild(IIOMetadataNode("ApplicationExtension").apply {
                        setAttribute("applicationID", "NETSCAPE")
                        setAttribute("authenticationCode", "2.0")
                        userObject = byteArrayOf(1, 0, 0)
                    })
                    root.appendChild(extensions)
                }
                metadata.setFromTree(format, root)
                writer.writeToSequence(IIOImage(image, null, metadata), null)
            }
            writer.endWriteSequence()
        } finally {
            writer.dispose()
        }
    }
}
