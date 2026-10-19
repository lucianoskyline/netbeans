/*
 * Copyright 2026 Luciano Lopes.
 * Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 */
package org.netbeans.modules.ai.chat;

import javax.swing.text.Document;
import org.netbeans.api.editor.mimelookup.MimeRegistration;
import org.netbeans.spi.editor.highlighting.HighlightsLayer;
import org.netbeans.spi.editor.highlighting.HighlightsLayerFactory;
import org.netbeans.spi.editor.highlighting.ZOrder;
import org.netbeans.spi.editor.highlighting.support.OffsetsBag;

/**
 * Registered for every MIME type; holds the inline completion suggestion,
 * painted by the editor as "virtual-text-prepend" before the end of line.
 */
@MimeRegistration(mimeType = "", service = HighlightsLayerFactory.class)
public final class GhostTextLayerFactory implements HighlightsLayerFactory {

    static OffsetsBag bag(Document doc) {
        synchronized (doc) {
            OffsetsBag bag = (OffsetsBag) doc.getProperty(GhostTextLayerFactory.class);
            if (bag == null) {
                bag = new OffsetsBag(doc);
                doc.putProperty(GhostTextLayerFactory.class, bag);
            }
            return bag;
        }
    }

    @Override
    public HighlightsLayer[] createLayers(Context context) {
        return new HighlightsLayer[] {
            HighlightsLayer.create(GhostTextLayerFactory.class.getName(),
                    ZOrder.SYNTAX_RACK.forPosition(1700), false, bag(context.getDocument()))
        };
    }
}
