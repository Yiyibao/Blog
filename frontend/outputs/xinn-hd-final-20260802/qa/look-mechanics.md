# Xinn look mechanics

- Stable anchor: both feet, lower skirt center, torso scale and cell baseline remain fixed.
- Gaze lead: the large physical eyes and eyelids lead each direction; the irises/pupils remain inside the original eye apertures.
- Follow-through: head and neck turn or pitch subtly after the eyes; shoulders and upper torso follow by a smaller amount.
- Crown and flowers: remain rigidly attached to the head with unchanged proportions. Hanging tassels lag the head motion slightly but never detach or swap identity.
- Hair and sleeves: follow the head/upper torso with restrained secondary motion. The lower skirt stays grounded.
- Up (000): pupils and eyelids clearly aim up, chin raises slightly, more lower face/neck is visible.
- Screen-right (090): eyes, nose and face turn unmistakably toward the viewer's right; the left side of the face becomes more visible.
- Down (180): pupils and eyelids aim down, chin lowers, crown remains unwarped.
- Screen-left (270): eyes, nose and face turn unmistakably toward the viewer's left; the right side of the face becomes more visible.
- Diagonals: even 22.5-degree interpolations between these cardinal pose families. No whole-sprite rotation, skew or affine tilt.
- Motion budget: each adjacent step changes eyes/head/upper torso by a similar small amount; no scale pop, registration jump, sudden occlusion or prop teleport.
