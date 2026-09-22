package spritepack;

import doja.ImageProvider;
import doja.ImageResource;

/** 由建置時精靈包提供支援的可選原生影像提供者。 */
public final class SpritePackImageProvider implements ImageProvider {

    public ImageResource openEncoded(byte[] data) {
        int id = SpriteRepository.findByEncoded(data);
        return id < 0 ? null : new SpriteResource(id);
    }

    public ImageResource openScratchpad(String uri) {
        int id = SpriteRepository.findByScratchpadUri(uri);
        return id < 0 ? null : new SpriteResource(id);
    }

    private static final class SpriteResource implements ImageResource {
        private int id;
        private javax.microedition.lcdui.Image image;

        SpriteResource(int spriteId) {
            id = spriteId;
            SpriteRepository.retain(spriteId);
            image = SpriteRepository.getNativeSheet(spriteId);
        }

        public javax.microedition.lcdui.Image getImage() {
            if (id < 0) return null;
            if (image == null) image = SpriteRepository.getNativeSheet(id);
            return image;
        }

        public int getWidth() { return id < 0 ? 0 : SpriteRepository.getWidth(id); }
        public int getHeight() { return id < 0 ? 0 : SpriteRepository.getHeight(id); }

        public void dispose() {
            if (id < 0) return;
            int released = id;
            id = -1;
            image = null;
            SpriteRepository.release(released);
        }
    }
}
