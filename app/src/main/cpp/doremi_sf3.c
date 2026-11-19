#include <stdlib.h>

#define STB_VORBIS_NO_STDIO
#include "stb_vorbis.c"

/* Decodes one SF3 sample. *output is malloc'd mono PCM; the caller frees it.
 * Returns the number of frames, or -1 on failure. */
int doremi_decode_vorbis(const unsigned char *data, int length, short **output) {
    int channels = 0;
    int sample_rate = 0;
    short *decoded = NULL;
    short *mono = NULL;
    int frames;
    int i;

    if (output == NULL) {
        return -1;
    }
    *output = NULL;
    if (data == NULL || length <= 0) {
        return -1;
    }

    frames = stb_vorbis_decode_memory(data, length, &channels, &sample_rate, &decoded);
    if (frames <= 0 || decoded == NULL || channels <= 0) {
        free(decoded);
        return -1;
    }
    if (channels == 1) {
        *output = decoded;
        return frames;
    }

    mono = (short *) malloc(sizeof(short) * (size_t) frames);
    if (mono == NULL) {
        free(decoded);
        return -1;
    }
    for (i = 0; i < frames; ++i) {
        int sum = 0;
        int channel;
        for (channel = 0; channel < channels; ++channel) {
            sum += decoded[i * channels + channel];
        }
        mono[i] = (short) (sum / channels);
    }
    free(decoded);
    *output = mono;
    return frames;
}
