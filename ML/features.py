#%%
from typing import *

import numpy as np
from numpy.fft import fft, fftfreq, rfft, rfftfreq

from scipy.stats import kurtosis, skew

class SpectrumValue:
    frequency: Any
    value: Any

    def __init__(self, frequency, value):
        self.frequency = frequency
        self.value = value

def get_amplitude_spectrum(data, framerate, max_framerate: Union[int, None]):
    if len(data.shape) == 2:
        data = data.sum(axis=1) / 2
    spectrum: np.ndarray = abs(rfft(data))
    freqs = rfftfreq(len(data),1./framerate)
    result = []
    for val_index, val in enumerate(spectrum):
        if max_framerate is None or freqs[val_index] <= max_framerate:
            result.append(SpectrumValue(freqs[val_index], val))
    return result

def get_features1d(feature2d):
    return [
        np.mean(feature2d, axis=1),
        np.min(feature2d, axis=1),
        np.max(feature2d, axis=1),
        np.median(feature2d, axis=1),
        np.var(feature2d, axis=1),
        skew(feature2d, axis=1),
        kurtosis(feature2d, axis=1),
    ]
