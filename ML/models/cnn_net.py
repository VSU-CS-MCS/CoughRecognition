#%%
from typing import *

import torch
import torch.nn as nn
import torch.nn.functional as F

class CoughNetCnn(nn.Module):
    def __init__(
        self,
        n_mfccs,
        time_size,
        n_classes,
        cnn_dropout=0.25,
        linear_dropout=0.1):
        super(CoughNetCnn, self).__init__()

        self.cnn_layers = nn.Sequential(
            nn.Conv2d(
                in_channels=1,
                out_channels=32,
                kernel_size=(2, 3)),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(kernel_size=(2, 3)),
            nn.Dropout2d(cnn_dropout, inplace=True),

            nn.Conv2d(
                in_channels=32,
                out_channels=64,
                kernel_size=(2, 3)),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(kernel_size=(2, 3)),
            nn.Dropout2d(cnn_dropout, inplace=True),

            nn.Conv2d(
                in_channels=64,
                out_channels=96,
                kernel_size=(2, 3)),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(kernel_size=(2, 3)),
            nn.Dropout2d(cnn_dropout, inplace=True))

        sample_cnn_input = torch.zeros(1, 1, n_mfccs, time_size)
        sample_cnn_output = self.cnn_layers(sample_cnn_input)
        sample_linear_in = torch.flatten(sample_cnn_output, 1)
        self.cnn_layers_shape = sample_linear_in.size()[1]

        self.linear_layers = nn.Sequential(
            nn.Linear(self.cnn_layers_shape, 120),
            nn.ReLU(inplace=True),
            nn.Dropout(linear_dropout),
            nn.Linear(120, n_classes))

    def forward(self, mfccs):
        batch_size, n_mfccs, time_size = mfccs.size()

        cnn_in = mfccs.view(batch_size, 1, n_mfccs, time_size)
        cnn_out = self.cnn_layers(cnn_in)

        linear_in = torch.flatten(cnn_out, 1)
        linear_out = self.linear_layers(linear_in)
        return linear_out
