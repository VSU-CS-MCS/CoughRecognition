#%%
from typing import *

import torch
import torch.nn as nn
import torch.nn.functional as F
#%%
class CoughNetCnn(nn.Module):
    def __init__(
        self,
        n_mfccs,
        n_classes,
        cnn_dropout=0.25,
        linear_dropout=0.1):
        super(CoughNetCnn, self).__init__()

        self.cnn_layers = nn.Sequential(
            nn.Conv2d(
                in_channels=1,
                out_channels=6,
                kernel_size=2),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(kernel_size=2),
            nn.Dropout2d(cnn_dropout, inplace=True),

            nn.Conv2d(
                in_channels=6,
                out_channels=12,
                kernel_size=2),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(kernel_size=2),
            nn.Dropout2d(cnn_dropout, inplace=True))
        self.cnn_layers_shape = n_mfccs * 12 * 12

        self.linear_layers = nn.Sequential(
            nn.Linear(self.cnn_layers_shape, 120),
            nn.ReLU(inplace=True),
            nn.Dropout(linear_dropout),
            nn.Linear(120, n_classes))

    def forward(self, mfccs):
        batch_size, n_mfccs, time_size = mfccs.size()

        cnn_in = mfccs.view(batch_size, 1, n_mfccs, time_size)
        cnn_out = self.cnn_layers(cnn_in)

        linear_in = cnn_out.view(-1, self.cnn_layers_shape)
        linear_out = self.linear_layers(linear_in)
        return linear_out
