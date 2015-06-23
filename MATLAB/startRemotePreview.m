function success =  startRemotePreview()
% success = startRemotePreview()
%
% Open a window on the remote Android device which shows a "preview" of the
% scene, and waits for further requestRemoteCapture() commands. Note it is
% not necessary to have opened the preview before issuing such commands.
% But it helps ;-)
%
% - - Output - - 
% success : boolean indicating whether it was successfully opened or not
%
% Rob Sumner - June 2015

consts = devCamConstants(); % load relevant paths
command = consts.am_pre; % pre-amble
success = adbshell(command);% send command to the shell, save output status