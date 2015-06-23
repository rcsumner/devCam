function consts =  devCamConstants()
% consts = devCamConstats()
%
% This function basically acts as an environment setting for running the
% devCam remote capture activity. Prior to running functions like 
% requestRemoteCapture() or even adbpull() you should populate this with
% the relevant directory locations for fields:
% .platform_tools_dir : where your Android platform-tools directory is on
%                       your computer
% .remote_dir : where on the remote device the devCam app creates its
%               directory, as an absolute path
%
% - - Output - -
% consts : a struct with relevant directories as fields.
%
% Rob Sumner - June 2015

consts = struct(); %initialize empty struct

consts.platform_tools_dir = '~/Developer/sdk/platform-tools/';
consts.remote_dir = '/mnt/shell/emulated/0/Pictures/devCam/';


% These are useful components of commands to pass through the adb shell
consts.am_pre = 'am start -n com.devcam/.RemoteCaptureActivity'; % common pre-amble for RemoteCapture commands
consts.START_INTENT = '-a REMOTE_START'; % intent to start RemoteCapture preview window on device
consts.CAPTURE_INTENT = '-a CAPTURE_REQUEST'; % intent to initiate an actual capture on device

end % end function