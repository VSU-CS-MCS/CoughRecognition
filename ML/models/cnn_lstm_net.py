#%%
from typing import *

import torch
import torch.nn as nn
import torch.nn.functional as F
#%%
class CoughNetCnnLstm(nn.Module):
    def __init__(
        self,
        n_mfccs):
        super(CoughNetCnnLstm, self).__init__()

        self.cnn_layers = nn.Sequential(
            nn.Conv2d(
                in_channels=1,
                out_channels=6,
                kernel_size=2),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(kernel_size=2),
            nn.Dropout2d(0.25, inplace=True))
        self.cnn_layers_shape = 6 * n_mfccs * 6

        self.lstm_layers = nn.LSTM(
            input_size=self.cnn_layers_shape,
            hidden_size=10,
            num_layers=1,
            batch_first=True)

        self.linear_layers = nn.Sequential(
            nn.Linear(10, 120),
            nn.ReLU(inplace=True),
            nn.Linear(120, 2))

    def forward(self, x):
        batch_size, channel_size, mfccs_size, time_size = x.size()

        cnn_in = x.view(batch_size * time_size, channel_size, mfccs_size)
        cnn_out = self.cnn_layers(cnn_in)

        rnn_in = x.view(batch_size, time_size, -1)
        rnn_out = self.lstm_layers(rnn_in)

        linear_in = rnn_out[:, -1, :]
        linear_out = self.linear_layers(linear_in)
        return linear_out
