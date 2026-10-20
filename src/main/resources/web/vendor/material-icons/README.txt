Material Icons (Google), Apache License 2.0 (see LICENSE), vendored so the editor page needs nothing
from outside (its Content-Security-Policy is default-src 'self').

material-icons.js holds only the SVG path data (24x24, filled) of the icons the editor uses, taken
from Google's Material Icons ("baseline" style): https://github.com/google/material-design-icons
The paths are unchanged; the file adds nothing but a name for each. To add an icon, copy its path
from the same set and give it a name here.
