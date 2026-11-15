#!/usr/bin/env python3
"""Allow FluidSynth to load SF3 files and decode them with doremi_decode_vorbis."""

import pathlib
import sys

MARKER = "DOREMI_SF3_STB_V1"

VERSION_CHECK = """if(sf->version.major == 3)
            {
#if !LIBSNDFILE_SUPPORT
                FLUID_LOG(FLUID_WARN,
                          "Sound font version is %d.%d but fluidsynth was compiled without"
                          " support for (v3.x)",
                          sf->version.major, sf->version.minor);
                return FALSE;
#endif
            }"""

VERSION_REPLACEMENT = """if(sf->version.major == 3)
            {
                /* DOREMI_SF3_STB_V1: samples are decoded by doremi_decode_vorbis. */
            }"""

STUB = """#else
static int fluid_sffile_read_vorbis(SFData *sf, unsigned int start_byte, unsigned int end_byte, short **data)
{
    return -1;
}
#endif"""

STUB_REPLACEMENT = r"""#else
/* DOREMI_SF3_STB_V1 */
extern int doremi_decode_vorbis(const unsigned char *data, int length, short **output);

static int fluid_sffile_read_vorbis(SFData *sf, unsigned int start_byte, unsigned int end_byte, short **data)
{
    unsigned int length;
    unsigned char *compressed;
    int frames;

    if(data == NULL || end_byte < start_byte || end_byte > sf->samplesize || start_byte > sf->samplesize)
    {
        FLUID_LOG(FLUID_ERR, "Ogg Vorbis data offsets exceed sample data chunk");
        return -1;
    }

    length = end_byte - start_byte;
    if(length == 0)
    {
        *data = NULL;
        return 0;
    }

    compressed = (unsigned char *) FLUID_MALLOC(length);
    if(compressed == NULL)
    {
        return -1;
    }
    if(sf->fcbs->fseek(sf->sffd, (fluid_long_long_t) (sf->samplepos + start_byte), SEEK_SET) == FLUID_FAILED)
    {
        FLUID_FREE(compressed);
        FLUID_LOG(FLUID_ERR, "Failed to seek to compressed sample position");
        return -1;
    }
    if(sf->fcbs->fread(compressed, (fluid_long_long_t) length, sf->sffd) == FLUID_FAILED)
    {
        FLUID_FREE(compressed);
        FLUID_LOG(FLUID_ERR, "Failed to read compressed sample");
        return -1;
    }

    frames = doremi_decode_vorbis(compressed, (int) length, data);
    FLUID_FREE(compressed);
    if(frames < 0)
    {
        FLUID_LOG(FLUID_ERR, "Failed to decode Ogg Vorbis sample");
    }
    return frames;
}
#endif"""


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: patch_sf3.py FLUIDSYNTH_SRC", file=sys.stderr)
        return 1
    path = pathlib.Path(sys.argv[1]) / "src" / "sfloader" / "fluid_sffile.c"
    if not path.exists():
        print(f"missing {path}", file=sys.stderr)
        return 1
    text = path.read_text()
    if MARKER in text:
        return 0
    if VERSION_CHECK not in text or STUB not in text:
        print("FluidSynth sources did not match the SF3 patch", file=sys.stderr)
        return 1
    text = text.replace(VERSION_CHECK, VERSION_REPLACEMENT, 1).replace(STUB, STUB_REPLACEMENT, 1)
    path.write_text(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
