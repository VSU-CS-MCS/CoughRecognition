#%%
from typing import *

import torch
from torch.functional import Tensor
import torch.nn as nn
import torch.nn.functional as F
from torch.nn.modules import dropout
#%%
class CoughNetCnnLstm(nn.Module):
    def __init__(
        self,
        n_mfccs,
        time_size,
        n_classes,
        cnn_dropout=0.1,
        lstm_hidden_size=100,
        lstm_num_layers=5,
        lstm_dropout=0.1,
        linear_dropout=0.1):
        super(CoughNetCnnLstm, self).__init__()

        self.cnn_layers = nn.Sequential(
            nn.Conv2d(
                in_channels=1,
                out_channels=32,
                kernel_size=(2, 2)),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(kernel_size=(2, 2)),
            nn.Dropout2d(cnn_dropout, inplace=True),

            nn.Conv2d(
                in_channels=32,
                out_channels=64,
                kernel_size=(2, 3)),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(kernel_size=(2, 2)),
            nn.Dropout2d(cnn_dropout, inplace=True),

            nn.Conv2d(
                in_channels=64,
                out_channels=96,
                kernel_size=(2, 3)),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(kernel_size=(2, 2)),
            nn.Dropout2d(cnn_dropout, inplace=True))

        sample_cnn_input = torch.rand(2, 1, n_mfccs, time_size)
        sample_cnn_output = self.cnn_layers(sample_cnn_input)
        batch_size, cnn_channels, cnn_mfccs, cnn_time = sample_cnn_output.size()

        self.rnn_input_size = cnn_channels * cnn_mfccs

        self.lstm_layers = nn.LSTM(
            input_size=self.rnn_input_size,
            hidden_size=lstm_hidden_size,
            num_layers=lstm_num_layers,
            dropout=lstm_dropout,
            batch_first=True)
        self.dense_layers = nn.Sequential(
            nn.Linear(lstm_hidden_size, 120),
            nn.ReLU(inplace=True),
            nn.Dropout(linear_dropout),
            nn.Linear(120, n_classes))

    def forward(self, mfccs):
        batch_size, n_mfccs, time_size = mfccs.size()

        cnn_in = mfccs.view(batch_size, 1, n_mfccs, time_size)
        cnn_out = self.cnn_layers(cnn_in)
        batch_size, cnn_channels, cnn_mfccs, cnn_time = cnn_out.size()

        rnn_in = self.cnn_to_lstm(cnn_out, batch_size, cnn_time)
        rnn_out, (h_n, c_n) = self.lstm_layers(rnn_in)

        dense_in = rnn_out[:, -1, :]
        dense_out = self.dense_layers(dense_in)
        return dense_out

    def cnn_to_lstm(self, cnn_out: Tensor, batch_size, time_size):
        rnn_in = cnn_out.transpose(2, 3).transpose(2, 1).view(batch_size, time_size, self.rnn_input_size)
        return rnn_in

